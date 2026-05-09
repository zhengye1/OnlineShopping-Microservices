# Lesson 04 — Strangler Fig: Extract User Service

> **Goal**: 由 monolith 抽 `User` entity + auth flow 出嚟做獨立 user-service。Apply L3 嘅 outbox pattern + cross-service snapshot pattern 落地。Spring Boot 3.5 + Java 21 + JWT stateless + Spring Security 6 + Flyway + Testcontainers 整套 production-grade stack 第一次見真章。
>
> **L1-L3 全部係 design / doc，L4 第一個真正 keyboard-touches-Java 嘅 lesson。** 9 個 commit 細粒度 split — 每 commit 一個 logical milestone，方便 review + rollback。

---

## Learning Objectives

完成本 lesson 後，你應該答得出：

1. Strangler Fig 三 phase migration 喺 course 入面點 simulate（fast-forward）
2. JPA entity design：點解揀 `IDENTITY`（vs Snowflake）、VARCHAR 長度 sizing、`@Version` optimistic lock、`@Enumerated(EnumType.STRING)` 關鍵
3. Spring Security 6 + JWT stateless auth：issuance vs validation 嘅 service split + 點解 BCrypt cost = 12
4. JJWT 0.13 API 嘅典型用法（`Jwts.builder().signWith()` / `Jwts.parser().verifyWith().requireIssuer()`）
5. Flyway versioned migrations 嘅紀律（schema-as-code，`flyway_schema_history` 點 work）
6. Outbox pattern **真實落地**：`@Transactional(propagation=MANDATORY)` 點 enforce atomicity + scheduled poller pattern
7. `@RestControllerAdvice` global exception handling — 點 sanitize Spring 嘅 verbose default error response
8. Testcontainers integration testing：`@ServiceConnection` 自動 inject DataSource + 點寫 invariant tests (e.g. user enumeration prevention)
9. 兩個 production-critical Spring Boot trap：`/error` permitAll 必需 + Spring Security 6 default 401 → 403 mismatch

---

## 1. Project Setup (L4.1 — L4.2)

### 1.1 Pom additions on top of L2 placeholder

User-service 由 L2 嗰個 hello-world stub 升級成 production-grade service，需要加：

| 類別 | Dependencies |
|------|--------------|
| Web + Validation | `spring-boot-starter-web` + `spring-boot-starter-validation` |
| Data + DB | `spring-boot-starter-data-jpa` + `mysql-connector-j` (runtime) + `flyway-core` + `flyway-mysql` |
| Security + JWT | `spring-boot-starter-security` + `jjwt-api/impl/jackson` (0.13.0) |
| DX | `lombok` (compile-only with annotation processor) |
| Test | `spring-boot-starter-test` + `spring-boot-testcontainers` + `spring-security-test` + `testcontainers-mysql` |

**Version pinning**: 大部分 dep 由 `spring-boot-dependencies` BOM (root pom) 控制 — service pom 唔需要寫 version。**呢個係 L2 BOM payoff 嘅 first concrete demo**：bump Spring Boot version 一行，全 fleet 跟住升。

**JJWT exception**：`io.jsonwebtoken:*` 唔喺 Spring Boot BOM 入面 → 喺 service pom 嘅 `<properties>` pin `<jjwt.version>`。Same for Testcontainers (`<testcontainers.version>`)。

### 1.2 application.yml — production-grade defaults

幾個關鍵 setting：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate     # ⭐ Flyway 控制 schema, JPA 只 validate entity ↔ table mapping
    open-in-view: false       # ⭐ 禁用 OSIV — production grade，強迫 explicit transaction boundary
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration

server:
  port: 8081                  # monolith 8080, user-service 8081
  error:
    include-message: always              # 將 ResponseStatusException reason 入返 response body
    include-binding-errors: always       # 將 @Valid validation errors 入返 response body

app:
  jwt:
    secret: ${JWT_SECRET:dev-only-secret-...}     # env-overridable; dev fallback labeled "dev-only"
    expiration-minutes: ${JWT_EXPIRATION_MINUTES:60}
    issuer: user-service
```

**`open-in-view: false` 點解 production grade**：default `true` 會 keep Hibernate session open until view rendering — 容易撞 lazy loading inside controller (after transaction closed)。`false` force 你 explicit `@Transactional` boundary，bug surface 早。

### 1.3 Local infrastructure — `docker-compose.yml`

`services/user-service/docker-compose.yml` 起 MySQL 8.4 + Adminer（DB browser）：

```yaml
services:
  mysql:
    image: mysql:8.4
    ports: ["3307:3306"]      # avoid clash with system MySQL on 3306
    healthcheck: ...
    volumes:
      - user_service_mysql_data:/var/lib/mysql
  adminer:
    image: adminer:5
    ports: ["8090:8080"]      # http://localhost:8090
```

**Named volume** (`user_service_mysql_data`) 唔污染 working dir，無需 .gitignore handling。`docker compose down -v` 一鍵 reset。

---

## 2. Schema Design (L4.3)

### 2.1 V1 migration

```sql
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,        -- IDENTITY
    email         VARCHAR(320) NOT NULL,                       -- RFC 5321 max
    password_hash VARCHAR(255) NOT NULL,                       -- BCrypt(60) + algo prefix room
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER',        -- enum stored as STRING
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version       BIGINT       NOT NULL DEFAULT 0,             -- @Version optimistic lock
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.2 Design decisions reasoned

| Decision | Choice | Why |
|----------|--------|-----|
| Table name | `users` (plural) | `user` 係 MySQL/Postgres reserved keyword |
| ID strategy | `IDENTITY` (auto-increment) | L4 simplicity — single service, no cross-service ID collision risk yet. **L5+ refactor to Snowflake** when product/order/etc. arrive |
| `email` length | `VARCHAR(320)` | RFC 5321 max email length |
| `password_hash` length | `VARCHAR(255)` | BCrypt = 60 chars; `{bcrypt}` prefix room for `DelegatingPasswordEncoder` algo upgrades; 255 唔 trigger MySQL TEXT promotion |
| `role` representation | `VARCHAR(32) DEFAULT 'USER'` + JPA `@Enumerated(EnumType.STRING)` | **Critical**: never use `EnumType.ORDINAL`. Reordering enum values = silent data corruption |
| `version` | `BIGINT DEFAULT 0` + JPA `@Version` | Optimistic locking infrastructure for L6 race condition lesson |
| Charset | `utf8mb4` (not `utf8`) | MySQL `utf8` is broken (3-byte) — `utf8mb4` is real UTF-8 (4-byte, supports emoji) |

### 2.3 JPA entity wiring

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)             // ⭐ STRING not ORDINAL
    @Column(nullable = false, length = 32)
    private Role role = Role.USER;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;
}
```

`@CreationTimestamp` / `@UpdateTimestamp` 由 Hibernate (not JPA spec) 提供 — application-level timestamp generation。Cf. SQL `DEFAULT CURRENT_TIMESTAMP` 由 DB 控制。我哋兩邊都做（defense in depth）。

---

## 3. Auth Layer (L4.4)

### 3.1 The two halves of JWT

**Critical insight (per L4 Q1 grading)** — JWT 喺 microservices 嘅 split：

| Concern | Lives in |
|---------|----------|
| **Token issuance** (sign) | **Only user-service** (它 own user identity) |
| **Token validation** (verify signature) | **Every microservice** (each service 獨立 verify) |
| **Secret distribution** | HS256 share single secret (course MVP) → RS256 user-service has private key, others get public key (L18+) |

**Why this matters**: validation 唔需要 callback to user-service → 唔變 single point of failure。每個 service 收 request 自己 decode + verify → fully stateless。

### 3.2 Token claim shape

```
{
  "sub": "42",                    // user ID (NOT username — username is mutable, ID is canonical)
  "iss": "user-service",          // issuer
  "iat": 1715000000,              // issued at (UTC epoch seconds)
  "exp": 1715003600,              // expiration (UTC epoch seconds)
  "role": "USER"                  // RBAC role
}
```

`sub` claim **唔放 email/username** — 呢啲 mutable，會引致 token 過期之前用戶改 email 後 token 失效。User ID 係 canonical + immutable。

### 3.3 BCrypt cost factor

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);     // ~250ms per hash on modern CPU
}
```

| Cost | ms per hash | Use |
|------|-------------|-----|
| 10 (default) | ~60ms | Spring's default, baseline |
| **12 (我哋用)** | ~250ms | Sweet spot — slow enough to deter brute force, fast enough for UX |
| 13-14 | 500ms-1s | Production hardening |

**Counter-intuitive**: `~250ms login` sounds slow, but it's **deliberately slow** to make password attacks expensive. Modern hardware can hash millions of low-cost BCrypts per second; cost 12 reduces that to thousands → effectively defeats GPU-accelerated dictionary attacks。

### 3.4 SecurityFilterChain config

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(eh -> eh
            .authenticationEntryPoint((req, res, ex) ->
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/auth/**", "/health", "/actuator/**", "/error").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
}
```

**Three production-critical configs**:
1. `STATELESS` session — no `JSESSIONID` cookie ever issued
2. `/error` in permitAll — see Section 6 trap
3. Custom 401 entry point — see Section 6 trap

---

## 4. Endpoints + DTOs (L4.5)

3 endpoints under user-service:

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `POST /auth/register` | ❌ public | Create account + return JWT (auto-login) |
| `POST /auth/login` | ❌ public | Verify credentials + return JWT |
| `GET /users/me` | ✅ JWT required | Return current user's profile |

### 4.1 DTO records (Java 17+)

All request/response DTOs are Java `record` — minimum boilerplate:

```java
public record RegisterRequest(
        @Email @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 100) String password
) {}

public record AuthResponse(
        String token, Long userId, String email, Role role, long expiresInSeconds
) {}

public record UserResponse(
        Long id, String email, Role role, Instant createdAt
        // ⭐ password_hash, version, updatedAt 故意 EXCLUDE — 安全 + 最小暴露
) {}
```

`@Valid` annotation at controller boundary triggers Bean Validation。Failure → `MethodArgumentNotValidException` → handled by GlobalExceptionHandler (Section 6)。

### 4.2 Service-level security invariant: no user enumeration

```java
// AuthService.login() — both fail paths return IDENTICAL message
User user = userRepo.findByEmail(req.email())
        .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid credentials"));

if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
    throw new ResponseStatusException(UNAUTHORIZED, "Invalid credentials");
}
```

**Critical**: 「user not found」同「wrong password」必須返 byte-for-byte identical 401 + message。Otherwise, attacker probes which emails exist by response diff (timing or message)。**Lock down via test** (`login_wrongPassword_throws401` asserts identical reason)。

---

## 5. Outbox Pattern Implementation (L4.6)

L3 design lesson 嘅真實落地。

### 5.1 V2 schema

```sql
CREATE TABLE outbox_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    event_type    VARCHAR(64)  NOT NULL,                              -- e.g. 'UserCreated'
    aggregate_id  VARCHAR(64)  NOT NULL,                              -- e.g. user.id
    payload       TEXT         NOT NULL,                              -- JSON
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at  TIMESTAMP(6) NULL,                                  -- NULL = pending
    PRIMARY KEY (id),
    KEY idx_outbox_pending (published_at, id)                         -- composite for poller hot path
) ...;
```

### 5.2 The MANDATORY trick

```java
@Service
public class OutboxService {
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String eventType, String aggregateId, Object payload) {
        // ...
    }
}
```

**`MANDATORY` (not `REQUIRED`)** — if caller is NOT inside an existing transaction, Spring throws `IllegalTransactionStateException` immediately at runtime。

**Why this enforcement matters**: outbox pattern 嘅 atomic guarantee 完全 depend on entity write + outbox write 共享同一 transaction。`REQUIRED` 會 silently 起新 transaction，破壞 invariant。`MANDATORY` 強迫 caller 自己有 `@Transactional`，bug surface 早。

### 5.3 Caller wiring

```java
// AuthService.register() — outbox in same @Transactional as user save
@Transactional
public AuthResponse register(RegisterRequest req) {
    if (userRepo.existsByEmail(req.email())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
    }
    User user = User.builder()...build();
    user = userRepo.save(user);

    outboxService.record(                          // same tx as user save
            UserCreatedEvent.TYPE,
            user.getId().toString(),
            new UserCreatedEvent(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt())
    );

    return buildAuthResponse(user);
}
```

If anything throws after this point (e.g. JWT signing fails) → entire transaction rolls back → **both** user row + outbox row disappear → no orphan event。

### 5.4 Background poller

```java
@Component
public class OutboxPoller {
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxRepo.findTop100ByPublishedAtIsNullOrderByIdAsc();
        if (pending.isEmpty()) return;

        Instant now = Instant.now();
        for (OutboxEvent ev : pending) {
            log.info("[outbox] PUBLISH id={} type={} aggregate={} payload={}",
                    ev.getId(), ev.getEventType(), ev.getAggregateId(), ev.getPayload());
            // L7+: replace log with kafkaTemplate.send(topic, ev.getId(), ev.getPayload())
            ev.setPublishedAt(now);
        }
        outboxRepo.saveAll(pending);
    }
}
```

L4 嘅 poller 只係 log（prove pattern works end-to-end）；L7+ 換成真 Kafka producer。

**Concurrency note**: 若 user-service horizontal scale (multiple instances)，要將 read 升級成 `SELECT ... FOR UPDATE SKIP LOCKED`（PostgreSQL）或者 row-level lock，避免重複 publish。L4 single instance 唔需要。

### 5.5 `@EnableScheduling` 必需

`UserServiceApplication` 加 `@EnableScheduling` 先 activate `@Scheduled`。漏咗 → poller 永遠唔跑，event 永遠 pending。

---

## 6. Exception Handling Hardening (L4.7)

### 6.1 Trap #1 — `/error` 必須 permitAll

**Symptom**: 任何 `ResponseStatusException` 都俾 client 返 403（無論 throw 嘅係 401/404/409 全部）。

**Root cause**: Spring Boot 嘅 `BasicErrorController` 用 internal `RequestDispatcher.forward(/error)` render error response。呢個 forward **重新 evaluate SecurityFilterChain**。如果 `/error` 唔喺 `permitAll`，匿名 forward 被 reject → 403 overwrite 原本嘅 status。

**Fix**: 加 `/error` 落 `requestMatchers(...).permitAll()` list。

**Detection**: 第二次 register 嗰陣冇任何 application log（請求未到 controller）+ Spring Security DEBUG log 出 `Securing POST /error` 緊接 `Http403ForbiddenEntryPoint: Rejecting access`。

### 6.2 Trap #2 — Spring Security 6 default 401 → 403 mismatch

**Symptom**: Anonymous request to `/users/me` 返 403 instead of 401。

**Root cause**: Spring Security 6 default `Http403ForbiddenEntryPoint`。對 stateless JWT API，**語意應該係 401 (「冇 credentials」)** — 403 嘅語意係「authorized 但無權限」。

**Fix**: 加 custom `AuthenticationEntryPoint` returning 401。

**UX impact**: Frontend / mobile client 收 401 → trigger re-login flow；收 403 → show "access denied" page。**錯 status code = 錯 UX。**

### 6.3 Sanitize Spring's verbose error response

**Default Spring Boot validation error**：

```json
{
    "errors": [{
        "codes": ["Email.registerRequest.email", "Email.email", "Email.java.lang.String", "Email"],
        "arguments": [{...nested...}],
        "bindingFailure": false,
        "code": "Email"
    }]
}
```

**Production** wants:
```json
{
    "errors": [
        {"field": "email", "message": "must be a well-formed email address"}
    ]
}
```

**Fix**: `@RestControllerAdvice` GlobalExceptionHandler — 兩個 `@ExceptionHandler` methods:
- `MethodArgumentNotValidException` → map Spring `FieldError` → custom `FieldErrorDto(field, message)` only
- `ResponseStatusException` → unified envelope `ApiError(timestamp, status, error, message, errors, path)`

**Static analyzer XSS warning** — `req.getRequestURI()` flagged as XSS sink。Defense-in-depth concern. JSON API responses 唔係 真 XSS vector (Content-Type: application/json, browser 唔 render JSON as HTML)。Suppress with `@SuppressWarnings("CWE-79")` + 解釋 comment。

---

## 7. Testing Strategy (L4.8)

兩 layers，唔同責任：

### 7.1 Unit tests (Mockito) — `AuthServiceTest`

```java
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock UserRepository userRepo;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock JwtProperties jwtProps;
    @Mock OutboxService outboxService;
    @InjectMocks AuthService authService;

    @Test
    void register_newEmail_succeeds() {
        when(userRepo.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("hunter2!23")).thenReturn("$2a$12$encoded");
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });
        when(jwtService.issueToken(any(User.class))).thenReturn("fake.jwt.token");
        when(jwtProps.getExpirationMinutes()).thenReturn(60L);

        AuthResponse resp = authService.register(req);

        assertThat(resp.userId()).isEqualTo(42L);
        verify(outboxService).record(eq("UserCreated"), eq("42"), any());
    }
}
```

**Pure unit** — no Spring context, no DB, all dependencies mocked。Fast (~1 sec for 5 tests)。

### 7.2 Integration tests (Testcontainers + MockMvc)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("user_service_test");
}
```

`@ServiceConnection` (Spring Boot 3.1+) automatically wires the container's JDBC URL/credentials into Spring `DataSource` bean。Flyway runs V1+V2 migrations against fresh DB each test JVM。

**Real Spring Boot context, real MySQL, real Flyway, MockMvc HTTP layer** — catches integration bugs that unit tests miss (e.g. SQL syntax errors, JPA mapping mismatches, Spring Security wiring)。

### 7.3 Invariant tests — the high-ROI pattern

```java
@Test
void login_unknownEmail_throws401() {
    ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> authService.login(req));
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(ex.getReason()).isEqualTo("Invalid credentials");
}

@Test
void login_wrongPassword_throws401() {
    // user exists but password matches() returns false
    ResponseStatusException ex = catchThrowableOfType(...);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(ex.getReason()).isEqualTo("Invalid credentials");      // ⭐ IDENTICAL
}
```

**Lock down security invariant**: 兩個 fail path 必須返 byte-for-byte identical reason。將來邊個 dev 「heroically」改 message 變得 more helpful，呢兩個 test 即時紅 → catch user enumeration regression。

**Senior insight**: unit test 嘅最大 ROI 唔係 coverage %，係**捕獲 invariant 嘅能力**。Trivial CRUD path test 99% case 都係 noise；security/business invariant test 係 gold。

### 7.4 Test config caveat

```yaml
# application-test.yml
app:
  jwt:
    secret: test-only-secret-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    expiration-minutes: 5            # ← test profile ≠ default 60
```

Tests must be aware of test profile config. Better practice: `@Autowired JwtProperties` and reference actual config in assertions, not hardcoded numbers。

---

## 8. Bugs Hit + War Stories

L4 撞咗 5 個 production-grade bugs，每個都係 transferable lesson：

| # | Bug | Root cause | Lesson |
|---|-----|------------|--------|
| 1 | Spring Boot run hits parent pom 冇 main class | `-pl X -am spring-boot:run` 將 goal apply 落 reactor root | Multi-module 用 `mvn install` 一次 + `mvn -pl X spring-boot:run` (no `-am`) |
| 2 | Second register returns 403 instead of 409 | `/error` internal forward re-evaluates SecurityFilterChain, falls into `anyRequest().authenticated()` | Always permitAll `/error` for stateless JWT API |
| 3 | Anonymous `/users/me` returns 403 instead of 401 | Spring Security 6 default `Http403ForbiddenEntryPoint` | Add custom 401 `AuthenticationEntryPoint` for JWT API |
| 4 | Verbose Spring validation error response | Default `BasicErrorController` returns Spring internal `BindingResult` shape | `@RestControllerAdvice` to sanitize → clean `(field, message)` records |
| 5 | `expiresInSeconds` test mismatch (300 vs 3600) | Test profile uses 5 min expiration, hardcoded 3600 in test | Wire `JwtProperties` into test, compute expected from actual config |

呢 5 個 bug 全部撞過 + fix 過 = 你 production-ready 嘅程度高過好多 candidate。**面試直接講 war story 出嚟**：

> "Strangler Fig 抽 user service 嗰陣，我撞過 Spring Boot 個 internal `/error` forward 同 SecurityFilterChain 互動嘅 trap — 任何 application exception 嘅 status code 都會被 403 overwrite。Debug 用 Spring Security DEBUG log 追到係 `Http403ForbiddenEntryPoint` reject `/error`。Fix 係 `/error` permitAll。呢個 trap 喺好多 SO question 出現過，但好難喺 unit test 度 surface — 必須 integration test 跑 full filter chain。"

---

## 9. Production Gaps Documented

L4 故意冇做嘅嘢（記低，面試講得出 trade-off）：

| Gap | Why deferred | When to do |
|-----|--------------|------------|
| **Monolith proxy controller** | Monolith 喺另一個 repo，touching 越界；Strangler Fig pattern 已 documented 喺 L3 | L18+ API Gateway lesson 一齊處理 |
| **Snowflake ID generation** | L4 single service, IDENTITY 夠用 | L5 product-service 開始有 cross-service ID concerns |
| **RS256 asymmetric JWT** | L4 single service, HS256 就 enough | L18+ when other services need to verify (cart, order) |
| **Postman collection** | Automated tests + curl examples 已 cover manual exploration | Optional — Bruno / Insomnia / Postman 揀一個喺 L20+ 補 |
| **Outbox cleanup cron** | L4 outbox 唔會大 (single service, low volume) | L7+ Kafka 真 publish 之後加 archival job |
| **`SKIP LOCKED` for parallel pollers** | L4 single instance | L15+ horizontal scale lesson |
| **Lazy password upgrade on login** | Course skip — single algo throughout | L18+ security hardening lesson |

---

## 10. Interview Prep / Resume Points

### 5 條典型問題答法

**Q1: How do you handle authentication in microservices?**
- Stateless JWT (HS256 → RS256 production)
- **Issuance** centralized (only user-service has signing key)
- **Validation** distributed (every service has JWT filter, verifies independently)
- No callback to user-service per request → no SPOF
- Token claims: `sub` (user ID, NOT username), `role`, `exp`, `iat`, `iss`

**Q2: How do you migrate a service from monolith to microservice without downtime?**
- Strangler Fig 3-phase: Dual-Write → Shadow-Read → Cutover
- Outbox pattern for atomic dual-write (entity + outbox row in same DB transaction)
- `@Transactional(propagation=MANDATORY)` enforces atomicity invariant at runtime
- Background poller publishes events; consumer-side idempotency by event id

**Q3: How do you ensure a service's `password_hash` is never accidentally exposed?**
- DTO whitelist (UserResponse excludes passwordHash field)
- BCrypt cost 12 (~250ms — deliberately slow for brute-force defense)
- DelegatingPasswordEncoder for algo upgrade flexibility
- Never log password values
- 401 on both unknown email + wrong password (no enumeration leak — locked down via unit test)

**Q4: What testing strategy do you use for Spring Boot microservices?**
- **Unit**: Mockito + AssertJ — fast (1 sec for 5 tests), tests business logic + invariants
- **Integration**: `@SpringBootTest` + Testcontainers (`@ServiceConnection` auto-wires DataSource) + MockMvc — catches Spring wiring bugs, real SQL execution, full filter chain
- **Invariant tests** > coverage % — security invariants (no enumeration, no leak) > trivial CRUD path tests

**Q5: What are the Spring Security 6 traps you've hit in production?**
- `/error` must be in permitAll — internal forward to BasicErrorController re-evaluates filter chain, default behavior overwrites 4xx/5xx with 403
- Default `Http403ForbiddenEntryPoint` returns 403 for anonymous requests; for JWT API, semantically correct is 401 + custom AuthenticationEntryPoint
- `SessionCreationPolicy.STATELESS` ESSENTIAL for JWT — no JSESSIONID cookies
- CSRF disable for stateless API (CSRF protection assumes session-cookie auth)

### Resume bullet candidates

- Extracted user authentication service from monolith using Strangler Fig pattern with transactional outbox: Spring Boot 3.5, Java 21, JPA + Flyway, JWT (HS256) stateless auth, BCrypt(cost=12) password hashing
- Implemented production-grade outbox pattern with `@Transactional(propagation=MANDATORY)` invariant enforcement; background `@Scheduled` poller dispatches events FIFO via composite index `(published_at, id)`
- Designed sanitized error response envelope via `@RestControllerAdvice` with field-level validation errors; debugged + fixed 2 Spring Security 6 traps (`/error` permitAll, custom 401 entry point)
- Achieved 10/10 test pass rate with Mockito unit tests + Testcontainers integration tests; locked down security invariants (user enumeration prevention) via test that asserts identical 401 reason for both fail paths

---

## 11. Homework / Reflection

完 lesson 之前自問（解答下節 L5 開始時 fold 入 collapsible block）：

1. `OutboxService.record()` 用 `Propagation.MANDATORY`。如果改成 `REQUIRED` 會點？寫一個刻意 bug 嘅 test case 證明 atomicity 失效。
2. `JwtService` 用 HS256 symmetric。L7+ 抽 cart-service 嗰陣，cart-service 點 verify 用戶 token？兩個 service 點 share secret？呢個方案有咩 production risk？
3. 你 `application-test.yml` 嘅 `app.jwt.expiration-minutes: 5`。如果 integration test 跑超過 5 分鐘，會發生咩事？點解我哋實際 OK？
4. `Role` enum 暫時得 3 個值 (USER/ADMIN/SELLER)。如果 product team 突然要 SUPER_ADMIN 同 MODERATOR 兩個新 role，schema migration 點寫？JPA entity 點改？舊 user 嘅 `role` column 會點？
5. Outbox poller 係 `@Scheduled(fixedDelay=1000)`。如果一個 batch publish 100 個 events 用咗 5 秒，下一個 batch 幾時跑？解釋 `fixedDelay` vs `fixedRate` 嘅分別 + 我哋揀邊個更啱 + 為何。

---

## 12. 下一步 — Lesson 05 預告

**L5 — Product Catalog Service**

- 抽 `Product` + `Category` + `ProductImage` 出嚟做獨立 service
- 開實際 `services/product-service/` Spring Boot project（用 L4 嘅 pom + config 做 template）
- **第一個 cross-service write trap**：product-service 出 `ProductCreatedEvent`，inventory-service 將要 consume — 介紹 Kafka local container preview
- Snowflake ID generation 開始落地（cross-service entity creation）
- Branch: `lesson-05-product-service`
- Deliverable: `services/product-service/` 跑得起 + `POST /products` work + `GET /products/{id}` work + 整套 outbox pattern reuse from L4

L4 嘅 outbox + JWT validation pattern 將會喺 product-service copy 一份（**可能 promote 個 SecurityConfig + JwtFilter 入 shared module**，視乎 boundary discipline）。

---

## References

- Spring Security 6 reference: https://docs.spring.io/spring-security/reference/index.html
- JJWT 0.12.x docs: https://github.com/jwtk/jjwt
- Testcontainers Spring Boot integration: https://java.testcontainers.org/modules/databases/jdbc/#using-springboot
- Martin Fowler, *Strangler Fig Application*: https://martinfowler.com/bliki/StranglerFigApplication.html
- Microservices.io, *Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html
- OWASP Authentication Cheat Sheet (user enumeration prevention): https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- Spring Boot 3.5 docs (server.error.* options): https://docs.spring.io/spring-boot/appendix/application-properties/index.html#appendix.application-properties.server
