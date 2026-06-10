# Lesson 08 — Resilience4j: Circuit Breaker + Retry + Fallback

**Branch:** `lesson-08-resilience4j`
**Builds on:** L7 (cart-service Feign clients + integration test scaffold + WireMock chaos infra)
**Service touched:** `cart-service`
**Library:** `resilience4j-spring-boot3 2.2.0`

---

## Learning Objectives

By the end of this lesson you should be able to:

1. Explain **cascade failure** and **thread pool exhaustion** as the production-grade motivation for circuit breaker — not just "downstream might fail."
2. Configure a `@CircuitBreaker` with the three trip dimensions: failure rate, slow call rate, and minimum number of calls.
3. Design a **fallback method** that distinguishes **semantic errors** (4xx — propagate) from **infrastructure failures** (5xx, timeout, CB OPEN — degrade).
4. Choose retry policy with **exponential backoff + jitter** and articulate why each parameter matters — especially the **retry storm** and **thundering herd** mechanisms.
5. Wire Resilience4j metrics through Spring Boot Actuator for production observability — `/actuator/circuitbreakers`, `/actuator/health` aggregation.
6. Avoid two specific traps: the **aspect order trap** (default Retry-outside-CB inflates failure rate) and the **ignore-exceptions trap** (config does not skip fallback routing).

---

## 1. The Cross-Service Failure Problem

L7 你 build 咗 cart-service → product-service / inventory-service via Feign. 個架構 happy path 完美。但係 production 唔係 happy path。

### Concrete failure scenario

```
Inventory-service "半死" — process up，但 DB lock 緊
       │
       ▼
TCP connection OK → Feign send request → read response... 等
       │ (default Feign read timeout = 60s)
       ▼
60 秒之後 Feign throws SocketTimeoutException
```

問題嘅核心**唔係**「downstream 死」— 而係「**fast fail 同 slow fail**」嘅分別。

| 死法 | Feign 嘅反應 | Caller thread impact |
|---|---|---|
| **Fast fail** (process gone) | TCP connection refused → 即時 throw | 50ms 後返 — 你 thread 解放 |
| **Slow fail** (process stuck) | TCP OK，等 read response → 60s timeout | 你 thread stuck 60 秒 |

### Thread pool exhaustion math

```
Tomcat default config:
  server.tomcat.threads.max: 200
  server.tomcat.accept-count: 100   ← queue 滿就 reject

正常運作:
  Inbound QPS = 50, 每個 request 100ms
  In-flight threads: 50 × 0.1 = 5    ← 完全冇壓力

Inventory-service 半死:
  每個 request 等 60s timeout
  50 req/s × 60s = 3000 in-flight needed
       │
       ▼
  4 秒內 200 threads 全部 stuck
  6 秒後 accept queue 滿 → 503 reject incoming
       │
       ▼
  從 user 角度: cart-service 死咗
  從 metrics 角度: CPU < 5%, memory 正常 — 但 thread 全部 stuck
```

呢個係 **cascade failure 教科書 pattern** — 1 個 downstream slow → upstream thread pool exhaust → 用戶見到 cart-service 都死埋。

### Senior insight: CB 嘅 mental model

> **Circuit Breaker 唔係防止 downstream 死。係保護你自己唔被 downstream 拖死。**
>
> Downstream 死定唔死 — 唔關你事。你嘅 job 係：唔好用我嘅 thread 等死人嘢。

電路 analogy:

| 家用電路跳閘 | 微服務 Circuit Breaker |
|---|---|
| 過電流 → 物理跳閘 | Failure rate > threshold → state CLOSED → OPEN |
| 短路 → 瞬間爆量 | Slow call rate > threshold → trip |
| 跳閘後等 cool-down | Wait duration in OPEN state |
| 用戶手動 reset | Auto half-open probe → close on success |

---

## 2. Phase 1 — Add Resilience4j + bare @CircuitBreaker

### Dependencies

`services/cart-service/pom.xml`:

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-reactor</artifactId>
    <version>2.2.0</version>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-micrometer</artifactId>
    <version>2.2.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

⚠️ **Version trap**: 2.3.0 has `FallbackConfigurationOnMissingBean` introspection bug under Spring Boot 3.5.x. Downgrade to 2.2.0 — see [War Story #1](#war-stories).

### Why wrap in a service rather than annotate Feign directly?

Three approaches considered:

| | Approach | Trade-off |
|---|---|---|
| **A** | `@CircuitBreaker` on Feign interface | Concise; fallback must be `default` method on interface — awkward to wire deps + test |
| **B** | Spring Cloud Circuit Breaker `FallbackFactory` | Extra abstraction layer, harder to read |
| **C** | **Wrapper service** ⭐ | One more class, but fallback + metrics + tests stay clear |

**Same-class self-invocation trap**: Spring AOP proxies only intercept method calls that go **through the proxy**. If `CartService` calls its own private `@CircuitBreaker` method, the call bypasses the proxy entirely — CB silently does nothing. Extracting to a separate bean forces the call through the proxy.

### Bare CB wiring

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ResilientProductClient {
    private final ProductClient productClient;

    @CircuitBreaker(name = "productClient")
    public ProductSummary findById(Long userId, Long productId) {
        return productClient.findById(productId);
    }
}
```

At this point, an OPEN CB throws `CallNotPermittedException` directly to the caller — user sees 500. Phase 2 adds fallback to degrade gracefully.

### CB config — three trip dimensions

```yaml
resilience4j:
  circuitbreaker:
    instances:
      productClient:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        ignore-exceptions:
          - feign.FeignException$NotFound
```

| Setting | Why this value |
|---|---|
| `sliding-window-size: 10` | Small for fast trip in tests; production 100+ smooths the curve |
| `minimum-number-of-calls: 5` | Avoid 1-fail-trip on startup noise |
| `failure-rate-threshold: 50` | > 50% fail in window → OPEN |
| `slow-call-rate-threshold: 50` | > 50% slow (>2s) → OPEN even if not failing |
| `wait-duration-in-open-state: 30s` | Don't hammer recovering downstream |
| `permitted-...-half-open: 3` | Probe sample > 1 reduces noise in HALF_OPEN decision |
| `ignore-exceptions: NotFound` | 404 = downstream healthy, do NOT count as failure |

### CB state machine

```
              ┌──────────┐
              │  CLOSED  │ ← Normal traffic flows
              └────┬─────┘
                   │ failure rate > 50%
                   │  OR slow call rate > 50%
                   ▼
              ┌──────────┐
              │   OPEN   │ ← All calls fast-fail immediately
              │          │   (CallNotPermittedException)
              │          │   No thread wastes on dead downstream
              └────┬─────┘
                   │ 30s cool-down auto transition
                   ▼
              ┌───────────┐
              │ HALF_OPEN │ ← Send 3 probe calls
              └────┬──────┘
                   │
        ┌──────────┴──────────┐
   3/3 success           any fail
        │                     │
        ▼                     ▼
     CLOSED                  OPEN
```

---

## 3. Phase 2 — Fallback Method as State-Evolution Cache

A CB without fallback just turns "user waits 60s for 500" into "user immediately gets 500." Fallback is where you reclaim graceful degradation.

### Design probing — what should `findByIdFallback` return?

| Option | UX | Verdict |
|---|---|---|
| Throw 503 | User sees error | Honest but no graceful degradation |
| Return cached price | User proceeds with stale data | Good for non-critical reads |
| Placeholder `0` | User adds item but checkout shocks | UX disaster |
| Return null | Caller decides | Pushes decision down — same problem |

**Winning answer: hybrid — return cached if available, refuse otherwise, defer hard validation to checkout.** This is the Amazon "state-evolution cache" model:

```
add-to-cart:
  product-svc up   → live price → save as priceAtAddition
  product-svc down → cached fallback → save as priceAtAddition (possibly stale)

checkout (L9 saga):
  Hard re-verify against real product-svc — NO fallback allowed here
  If price drifted significantly → notify user
  If product still missing → fail order
```

### Where does the cache come from?

The crucial insight: **`cart_items` table is already a cache.** Every row stores `priceAtAddition` and `currency` from a prior successful product-service call. No Redis. No Caffeine. No new infrastructure.

```java
@CircuitBreaker(name = "productClient", fallbackMethod = "findByIdFallback")
public ProductSummary findById(Long userId, Long productId) { ... }

@Transactional(readOnly = true)
public ProductSummary findByIdFallback(Long userId, Long productId, Throwable cause) {
    // Semantic error — propagate the 404 distinction
    if (cause instanceof FeignException.NotFound) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Product not found: " + productId);
    }

    // Infrastructure failure — try the cache
    Optional<CartItem> cached = cartItemRepo.findById(new CartItemId(userId, productId));
    if (cached.isPresent()) {
        CartItem item = cached.get();
        log.warn("ResilientProductClient fallback HIT for userId={} productId={} cause={} "
                + "→ returning cached priceAtAddition={} {}",
                userId, productId, cause.getClass().getSimpleName(),
                item.getPriceAtAddition(), item.getCurrency());
        return new ProductSummary(productId, "[cached]", "CACHED-" + productId,
                item.getPriceAtAddition(), item.getCurrency(), "CACHED");
    }

    log.warn("ResilientProductClient fallback MISS for userId={} productId={} cause={} "
            + "→ no cached snapshot, returning 503",
            userId, productId, cause.getClass().getSimpleName());
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Product service unavailable; cannot add new item to cart");
}
```

### Three subtleties worth the read

1. **Fallback signature** — must mirror the main method plus a trailing `Throwable cause` parameter. Resilience4j matches by this shape; mismatches fail at startup.

2. **`@Transactional(readOnly = true)`** — fallback must NEVER itself fail in a way that propagates beyond the caller's intended error surface. Read-only transaction is the cheapest guarantee.

3. **Asymmetric observability fix** — your instinct may be to log the HIT path verbosely ("we saved you!") and the MISS path tersely. Production reality is the opposite: MISS is the case where a user **actually** sees an error. Both logs deserve equal context — userId, productId, cause class — because incident channels grep these to scope impact.

### UX boundary: only UPSERT degrades gracefully

| Scenario | Outcome |
|---|---|
| User has cart row, downstream dies | ✅ Degraded mode — use cached snapshot, increment quantity |
| User first-time add, downstream dies | ❌ 503 — no baseline price; refuse rather than guess |

This is intentional. The user already saw and accepted the cached price; incrementing is low-risk. A new add with no anchor price means **fabricating data the user has not seen**, which is the bigger problem.

---

## 4. Phase 3 — Retry with Exponential Backoff + Jitter

`@Retry` adds automatic recovery from transient failures **before** the CB fallback kicks in. But retry done wrong amplifies the problem.

### Retry storm

```
Normal:   1000 RPS hitting product-svc

Partial outage: product-svc slow → 30% fail
  no retry:        1000 RPS (30% see error)
  max-attempts=3:  1000 + 300×2 retries = 1600 RPS  ← +60% load
  max-attempts=5:  1000 + 300×4 retries = 2200 RPS  ← +120% load
       │
       ▼
product-svc 接受唔到，failure rate ↑ 50%
       │
       ▼
更多 retry → 更多 load → 死緊嘅 service 仲俾你加速咁推 ↓ → 完全死透
```

**Higher retry counts during partial outages accelerate cascade failure.** This is why `max-attempts: 3` is the production-default ceiling.

### Thundering herd

Without jitter, every client that failed at the **same instant** retries at the **same instant**:

```
T=0:     1000 client requests fail simultaneously (503)
T=1s:    1000 retries hit product-svc all at once → product-svc 再爆
T=2s:    1000 retries again → 再爆
       = Synchronized retry waves keep killing the service
```

Jitter spreads retry timing across a window so peak load divides by N:

```
T=0:        1000 fail
T=0.7-1.3s: 1000 retries spread across 600ms window
T=2.4-3.6s: spread across 1.2s window
       = Load distributed, downstream has room to recover
```

### Config

```yaml
resilience4j:
  retry:
    instances:
      productClient:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
        randomized-wait-factor: 0.3        # ±30% jitter
        retry-exceptions:
          - feign.FeignException$InternalServerError
          - feign.FeignException$BadGateway
          - feign.FeignException$ServiceUnavailable
          - feign.FeignException$GatewayTimeout
          - feign.RetryableException
          - java.net.SocketTimeoutException
          - java.io.IOException
        ignore-exceptions:
          - feign.FeignException$NotFound
          - feign.FeignException$BadRequest
          - feign.FeignException$Unauthorized
          - feign.FeignException$Forbidden
          - io.github.resilience4j.circuitbreaker.CallNotPermittedException
```

### What to retry vs. not — mental model

> **Retry premise: this failure has a reasonable chance of being transient. Retrying might succeed.**

| Exception | Retry? | Why |
|---|---|---|
| `FeignException.NotFound` (404) | ❌ | Product genuinely doesn't exist — retry 100x still 404 |
| `FeignException.BadRequest` (400) | ❌ | Request shape wrong — same outcome always |
| `FeignException.InternalServerError` (500) | ✅ | Server hiccup commonly transient |
| `SocketTimeoutException` | ✅ | Network jitter / transient lock — try again |
| `CallNotPermittedException` (CB OPEN) | ❌ | CB has already decided; retrying = instant fail × N |

### Aspect order trap — the critical config

`@Retry` and `@CircuitBreaker` interact through Spring AOP nesting order. **Default order is wrong**.

```
Default Resilience4j order:
  Retry → CircuitBreaker → call

Means:
  Retry catches failure
  → each retry attempt records to CB
  → 1 user request can record 3 CB failures
  → sliding window fills with retry attempts not user outcomes
  → CB trips prematurely
```

Fix:

```yaml
resilience4j.circuitbreaker.circuit-breaker-aspect-order: 1   # OUTER
resilience4j.retry.retry-aspect-order: 2                       # INNER
```

Lower number = higher priority = outer aspect. Now:

```
CB → Retry → call

Means:
  CB checks state first
  ├─ OPEN  → fast-fail (no thread wasted on retries against a dead CB)
  └─ CLOSED → enter Retry → 3 attempts → final outcome
  
  CB records 1 outcome per user request, regardless of retry count
  Sliding window math matches user experience
```

### Visible in test logs

The chaos test `addItem_transientError_recoversAfterRetry` confirms behavior end-to-end:

```
15:37:44.503  attempt 1 → 503     (wait 500ms × jitter)
15:37:45.229  attempt 2 → 503     (~726ms gap, with random spread)
15:37:45.757  attempt 3 → 200 ✅  (~528ms gap)
15:37:45.863  Cart insert: price=9999 CAD
```

User sees 201 Created. Total invisible recovery time ≈ 1.4 seconds.

---

## 5. Phase 4 — Metrics + Actuator Observability

Without observability, an OPEN circuit is invisible until users complain. Resilience4j-Micrometer + Spring Boot Actuator make every CB state a metric.

### Config

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, circuitbreakers, circuitbreakerevents
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
```

### What you get

| Endpoint | Use case |
|---|---|
| `GET /actuator/circuitbreakers` | Snapshot of every CB instance + state. SRE check during incident |
| `GET /actuator/circuitbreakerevents` | Audit log: STATE_TRANSITION, ERROR, SUCCESS events |
| `GET /actuator/metrics/resilience4j.circuitbreaker.calls` | Micrometer counters scraped by Prometheus |
| `GET /actuator/health` | CB state aggregates into health — wire to k8s readiness if downstream is critical |

### Sample `/actuator/circuitbreakers` response

```json
{
  "circuitBreakers": {
    "productClient": {
      "state": "CLOSED",
      "failureRate": "-1.0",
      "slowCallRate": "-1.0",
      "bufferedCalls": 0,
      "failedCalls": 0,
      "slowCalls": 0,
      "notPermittedCalls": 0
    }
  }
}
```

After a trip:

```json
{
  "circuitBreakers": {
    "productClient": {
      "state": "OPEN",
      "failureRate": "60.0",
      "bufferedCalls": 10,
      "failedCalls": 6,
      "notPermittedCalls": 47
    }
  }
}
```

`notPermittedCalls=47` = the CB has fast-failed 47 calls since trip, sparing 47 threads from 60-second hangs.

---

## 6. Sequence Diagrams

### CB flow on transient hiccup (recovered via retry)

```
User                CartController     CartService    ResilientProductClient    Feign        WireMock
  │                       │                  │                  │                  │              │
  ├─POST /cart/items────► │                  │                  │                  │              │
  │                       ├─addItem()──────► │                  │                  │              │
  │                       │                  ├─findById(uid,pid)► [CB CLOSED]──────►│              │
  │                       │                  │                  │ enter Retry      │              │
  │                       │                  │                  │   attempt 1───► │ ──► GET ────►│
  │                       │                  │                  │                  │ ◄── 503 ─────┤
  │                       │                  │                  │   wait 500ms×jitter             │
  │                       │                  │                  │   attempt 2───► │ ──► GET ────►│
  │                       │                  │                  │                  │ ◄── 503 ─────┤
  │                       │                  │                  │   wait 1s×jitter                │
  │                       │                  │                  │   attempt 3───► │ ──► GET ────►│
  │                       │                  │                  │                  │ ◄── 200 ─────┤
  │                       │                  │ ◄────ProductSummary                 │              │
  │                       │                  │ continue flow...                    │              │
  │                       │                  │ persist cart_item                   │              │
  │ ◄─── 201 Created ─────┤                  │                                     │              │
```

### CB flow on persistent outage (fallback HIT)

```
User                CartService    ResilientProductClient    Feign       WireMock     cart_items
  │                       │                  │                  │              │            │
  ├─addItem()──────────► │                  │                  │              │            │
  │                      ├─findById─────────► [CB CLOSED]──────►│              │            │
  │                      │                   enter Retry        │              │            │
  │                      │                   attempt 1───────► │ ──► GET ────►│            │
  │                      │                                       │ ◄── 503 ─────┤            │
  │                      │                   attempt 2-3 also 503                            │
  │                      │                   ↓ all retries exhausted                         │
  │                      │                   CB records 1 failure                            │
  │                      │                   route to findByIdFallback(cause=503)            │
  │                      │                   ├──────query cart_items──────────────────────► │
  │                      │                   │ ◄────CartItem(price=8000, currency=USD)─────┤
  │                      │                   return ProductSummary(cached)                  │
  │                      │ ◄────────────────                                                │
  │                      │ continue with cached price                                       │
  │                      │ persist increment                                                │
  │ ◄── 201 Created ─────┤                                                                  │
```

---

## 7. Testing Strategy

| Layer | Test | What it verifies |
|---|---|---|
| **Unit** | `CartServiceTest` (5 cases) | CartService business logic with mocked ResilientProductClient |
| **Integration — baseline** | `CartControllerIntegrationTest` 4 cases (L7) | Happy path, 404, 409, 401 |
| **Integration — chaos (Phase 2)** | `addItem_productServiceDown_existingCart_fallsBackToCachedPrice` | Fallback HIT path: cached price preserved |
| **Integration — chaos (Phase 2)** | `addItem_productServiceDown_newCart_returns503` | Fallback MISS path: 503 + no inventory call |
| **Integration — chaos (Phase 3)** | `addItem_transientError_recoversAfterRetry` | WireMock stateful scenarios — 503/503/200 → user sees 201 |
| **Observability (Phase 4)** | `ResilienceObservabilityTest` 2 cases | `/actuator/circuitbreakers` + health aggregation |

WireMock **stateful scenarios** (Phase 3 test) deserve a callout — production-grade chaos test pattern:

```java
wireMock.stubFor(get(...)
    .inScenario("transient-503")
    .whenScenarioStateIs("Started")
    .willReturn(aResponse().withStatus(503))
    .willSetStateTo("after-first-fail"));
```

Each request walks the state graph — first call returns 503 and moves the scenario to next state, second call returns 503 again, third call returns 200. Resilience4j's 3 attempts walk through all 3 stages.

---

## 8. War Stories

### War story #1 — Resilience4j 2.3.0 `FallbackConfigurationOnMissingBean` introspection bug

**Symptom**: Spring Boot startup throws `IllegalStateException: Error processing condition on io.github.resilience4j.springboot3.fallback.autoconfigure.FallbackConfigurationOnMissingBean.fallbackDecorators` followed by `Failed to introspect Class`.

**Cause**: `resilience4j-spring-boot3:2.3.0` has a class reference in its autoconfiguration that fails introspection under Spring Boot 3.5.x. The 2.3.0 release transitively pulls some 2.2.0 internals creating a version skew that breaks the `@ConditionalOnMissingBean` evaluation.

**Fix**: Downgrade to `resilience4j-spring-boot3:2.2.0`.

**Senior lesson**: Library version + framework version compatibility matrix must be verified. Maven Central having a newer version does not mean your framework supports it. Read the changelog and check the Spring Boot starter compatibility table before bumping.

### War story #2 — `ignore-exceptions` does NOT skip fallback routing

**Symptom**: Test `addItem_productNotFound_returns404` started returning 503 instead of 404 after adding `fallbackMethod` to `@CircuitBreaker`.

**Diagnosis**: We had configured `ignore-exceptions: [FeignException.NotFound]` thinking it would let 404 propagate untouched. It did keep the CB statistic clean — but **the fallback method was still invoked** because `@CircuitBreaker(fallbackMethod=...)` triggers fallback on every exception, including ignored ones.

```
ignore-exceptions:
  → CB statistics: 404 not counted as failure  ✅
  → Fallback routing: 404 still triggers fallback  ❌
```

**Fix**: distinguish in the fallback body itself:

```java
public ProductSummary findByIdFallback(Long userId, Long productId, Throwable cause) {
    if (cause instanceof FeignException.NotFound) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + productId);
    }
    // infrastructure-failure path...
}
```

**Senior pattern**: fallback methods must classify the cause as **semantic vs infrastructure**. Same shape of error from the caller's API surface (a thrown exception) can mean very different things to the user.

### War story #4 — Testcontainers `@Container` lifecycle + multi-class integration tests

**Symptom**: Running both `CartControllerIntegrationTest` and `ResilienceObservabilityTest` in the same `mvn test` invocation produced `CannotCreateTransactionException: Could not open JPA EntityManager for transaction` and `The driver has not received any packets from the server` on every test in the second class to run.

**Diagnosis**: `@Container` from Testcontainers JUnit Jupiter binds container lifecycle to the test class. Even on a static field shared via inheritance, the container is stopped when the first class's tests complete. The second class then tries to use a dead container.

**Fix**: Singleton container pattern — start in a static initializer, register a JVM shutdown hook for cleanup:

```java
@ServiceConnection
static final MySQLContainer<?> MYSQL;

static {
    MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("cart_service_test")
            .withUsername("test")
            .withPassword("test");
    MYSQL.start();
    Runtime.getRuntime().addShutdownHook(new Thread(MYSQL::stop));
}
```

Remove `@Container` and `@Testcontainers` annotations — we're managing lifecycle manually now.

**Senior lesson**: Per-class test infrastructure (Testcontainers default, WireMock @AfterAll cleanup) doesn't compose across multiple integration test classes. When you have JVM-shared resources (containers, mock servers, expensive bean contexts), use static initializer + shutdown hook to make lifecycle JVM-scoped. Bonus: total test time drops because the second class reuses the cached Spring context.

### War story #3 — Retry-outside-CB default ordering inflates failure rate

**Symptom**: Failure rate dashboard showing 60% when only 20% of user requests fail.

**Diagnosis**: Resilience4j default aspect order is `Retry(CB(call))`. Every retry attempt records its own CB outcome. 1 failing user request with 3 retries = 3 failed CB statistics. Sliding window math diverges from user experience.

**Fix**: Swap aspect order in application.yml:

```yaml
resilience4j.circuitbreaker.circuit-breaker-aspect-order: 1   # OUTER
resilience4j.retry.retry-aspect-order: 2                       # INNER
```

**Senior interview answer**: "Annotation order is invisible config — it controls Spring AOP nesting, and the default isn't what most engineers expect. With CB outside Retry, one user request maps to one CB statistic regardless of how many retries fire. Sliding window math then aligns with user experience."

---

## 9. Production Gaps Documented

What we **did not** build in L8, that real production would add:

| Gap | Why deferred |
|---|---|
| `@Bulkhead` — thread pool isolation | L8 covers CB + Retry. Bulkhead would prevent CartService's slow downstream from exhausting threads even if CB hasn't tripped yet. Worth adding before going to production at scale. |
| `@TimeLimiter` — explicit per-call timeout | Currently relying on Feign's default 60s read timeout. A 2-second explicit timeout via `@TimeLimiter` would trip the CB's slow-call detection more cleanly. |
| Apply CB + Retry to `InventoryClient` | L8 wraps ProductClient only. InventoryClient needs the same treatment — same wrapper service pattern. |
| Prometheus scraping + Grafana dashboard | Actuator endpoints are wired but no Prometheus is collecting yet. L20 will set this up at the infra layer. |
| Alert rules on CB state transitions | Once Prometheus is scraping, alert on `resilience4j_circuitbreaker_state{state="open"} > 0` to page on-call. |
| Per-CB metrics in CloudWatch (when AWS deployed) | Same data, different sink — covered in L21 AWS deployment. |

---

## 10. Interview Prep Q&A

### Q1 — Why does a circuit breaker matter? Isn't a timeout enough?

> A timeout limits how long a single call hangs, but every concurrent call still consumes a thread until that timeout expires. If downstream stays slow, every Tomcat worker stays stuck → thread pool exhaust → upstream looks dead even though it's healthy. Circuit breaker stops sending traffic after a failure threshold, freeing threads to fast-fail and protecting the upstream from collateral damage.

### Q2 — How do you decide failure threshold and window size?

> Failure-rate threshold and window size depend on traffic volume and risk tolerance. Higher window size smooths noise but trips slower. For a high-QPS service we'd use sliding-window=100 and failure-rate=50%. For a critical low-traffic endpoint we'd use sliding-window=10 with minimum-number-of-calls=5 to avoid 1-fail-trips while still reacting fast. Slow-call-rate threshold is equally important — a service can fail by being slow, not just by throwing.

### Q3 — Retry and circuit breaker together — what's the trap?

> Aspect order. Default Resilience4j ordering is Retry outside CB, which means each retry attempt records as a separate CB statistic. A failing user request with 3 retries records 3 failures, tripping the breaker prematurely and making the failure-rate dashboard misleading. The fix is swapping the order so CB sits outside Retry — one user request = one CB outcome, sliding window math matches user experience.

### Q4 — What's the difference between retrying and falling back?

> Retry assumes the failure is transient and the same request can succeed on a later attempt. Fallback assumes the call won't succeed and provides a degraded alternative. They complement each other: retry first for transient hiccups, fallback last for persistent outages. A well-designed system distinguishes semantic errors (4xx — don't retry, don't fallback, propagate) from infrastructure failures (5xx, timeouts — retry, then fallback).

### Q5 — How would you handle a downstream that's both slow AND failing?

> Resilience4j's slow-call-rate dimension is exactly this. Even if no calls actually fail, calls that exceed slow-call-duration-threshold count toward a separate trip threshold. A service that responds in 30 seconds with HTTP 200 is broken from your perspective even though it isn't erroring. We'd configure slow-call-rate-threshold=50% and slow-call-duration-threshold=2s for a typical user-facing call.

### Q6 — What about idempotency? Can you always retry safely?

> No. Retry is safe only for idempotent operations — same call, same effect, regardless of count. GET is naturally idempotent. POST is not, unless the server implements an idempotency key (the same `Idempotency-Key` header makes duplicate POSTs return the original response without doing the work twice). For our ProductClient lookup, retry is safe because it's a read. For something like payment processing, retry must include an idempotency-key header to avoid double-charging.

### Q7 — When would you NOT use a circuit breaker?

> When the call isn't on the critical path and a failure has no upstream impact. For example, an asynchronous background job that updates a non-critical metric doesn't need CB protection — it can fail silently and the system stays healthy. CB pays its complexity tax only when a slow downstream can corrupt your service's behavior or exhaust its threads.

---

## 11. Homework / Reflection

完 lesson 之前自問（解答喺 L9 開始時 fold 入 collapsible block）：

1. **Apply CB + Retry to `InventoryClient`** — same pattern as ProductClient. Design the fallback: what does inventory degraded mode look like? (Hint: the cart row doesn't have stock_quantity snapshot, so the cache pattern doesn't apply identically. Think about reservation tables — L9 will cover this in depth.)

2. **Add `@Bulkhead`** — configure a semaphore bulkhead on `findById` with `maxConcurrentCalls=10`. Write a chaos test that fires 20 concurrent requests against a slow WireMock stub and verify only 10 are admitted at a time. Discuss the trade-off vs `@TimeLimiter`.

3. **Tune for high-QPS production** — what `sliding-window-size`, `minimum-number-of-calls`, and `failure-rate-threshold` would you use for a service handling 1000 QPS? Explain reasoning. What about for a low-QPS critical service (10 QPS, payment processing)?

4. **Wire to Prometheus** — assume a Prometheus instance is already running. Write the `prometheus.yml` scrape job to pull metrics from cart-service `/actuator/prometheus`. Write a `PromQL` alert rule that pages on-call when `productClient` CB is OPEN for more than 5 minutes. What's the metric name?

5. **Distributed CB state via Redis** — Resilience4j is per-instance by default. If you have 10 cart-service replicas, each has its own CB state, so a downstream failure trips the CB on instance #1 but not instances #2-10. Until enough traffic flows through #2-10 to trip them too, those instances keep hammering the dead downstream. Design a solution: how would you share CB state across replicas? What's the trade-off vs the default per-instance approach?

<details>
<summary><strong>📖 Polished Solutions (L9 session fold-back)</strong></summary>

> 小V 嘅 cold-attempt notes: full surrender (0/180) — L8 hw set 故意全部係 staff-level question，需要 hands-on production experience (Bulkhead chaos test、PromQL 寫 alert、distributed CB design) 先答得齊。同 L6 35/170、L7 26/180 嘅 cadence 一致 — 先見過 polished solution，下次面試 frame 起就快。

---

### Q1 — Apply CB + Retry to `InventoryClient`

Pattern 同 ProductClient 一樣 — 但 **fallback strategy 完全唔同**，因為 cart_items 表唔係 inventory 嘅 cache。Inventory degraded mode 嘅 design decision 比 product 更難。

#### Why the cart_items cache trick doesn't carry over

```
ProductClient fallback:
  cart_items.priceAtAddition + currency → 重建 ProductSummary ✅

InventoryClient fallback:
  cart_items 冇 stock_quantity snapshot — 我哋只 snapshot price，唔 snapshot stock
  即使 snapshot 咗 stock，stock 變化快 — 30 秒前嘅 stock 已經唔可靠
  Risk: 用 stale stock allow add → 之後 checkout 撞 oversell → user 失望
```

**結論：inventory fallback 唔可以 fail-soft，必須 fail-fast。**

#### Decision matrix — inventory 嘅 degraded mode

| Scenario | Fallback strategy | Why |
|---|---|---|
| **Add-to-cart**（呢度）| Fail-fast 503 | 拒絕 < oversell — correctness > availability |
| **Browse product page (read-only stock)** | Cached stock OK，標 "approximate" | UX > correctness — browsing 唔 commit |
| **Checkout** (L9 saga) | Hard 503 + retry queue | Financial — 絕對唔可以 oversell |

#### Code

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ResilientInventoryClient {

    private final InventoryClient inventoryClient;

    @CircuitBreaker(name = "inventoryClient", fallbackMethod = "getStockFallback")
    @Retry(name = "inventoryClient")
    public InventoryStock getStock(Long productId) {
        return inventoryClient.getStock(productId);
    }

    public InventoryStock getStockFallback(Long productId, Throwable cause) {
        if (cause instanceof FeignException.NotFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Inventory record missing for product: " + productId);
        }

        // No cached snapshot, no safe fallback. Fail-fast.
        log.warn("ResilientInventoryClient fallback REJECTED for productId={} cause={} "
                + "→ inventory service unavailable, refusing add-to-cart",
                productId, cause.getClass().getSimpleName());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Inventory service unavailable; cannot verify stock");
    }
}
```

#### application.yml — separate CB instance, same threshold knobs

```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventoryClient:           # ← separate instance, independent state machine
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        ignore-exceptions:
          - feign.FeignException$NotFound
  retry:
    instances:
      inventoryClient:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
        randomized-wait-factor: 0.3
        retry-exceptions:
          - feign.FeignException$InternalServerError
          - feign.FeignException$BadGateway
          - feign.FeignException$ServiceUnavailable
          - feign.FeignException$GatewayTimeout
          - feign.RetryableException
          - java.net.SocketTimeoutException
        ignore-exceptions:
          - feign.FeignException$NotFound
          - io.github.resilience4j.circuitbreaker.CallNotPermittedException
```

#### Senior insight — failure isolation per downstream

每個 downstream 應該有**獨立** CB instance。原因：

> Inventory 死 → 唔應該 trip product 嘅 CB。
> Product 死 → 唔應該 trip inventory 嘅 CB。

如果共用 instance，相當於將兩個獨立故障 source 嘅 statistics 撈埋 — sliding window 入面 50% 失敗可能其實只係 inventory 100% 死、product 100% 健康，CB trip 全部 traffic 但其實 product 仲可以 serve。

#### 🎯 Interview talking point

> "We use one CircuitBreaker instance per downstream service. Sharing a single CB across multiple downstreams would conflate failure signals — a failing inventory service would trip the breaker for product calls too, even though product is healthy. Failure isolation is the entire reason we extracted these into separate microservices; the resilience layer must respect that boundary."

---

### Q2 — `@Bulkhead` with semaphore + chaos test

`@Bulkhead` 係 Resilience4j 嘅第 3 個 protection pattern（CB + Retry + Bulkhead），但解嘅問題完全唔同：

| Pattern | 解咩問題 |
|---|---|
| `@CircuitBreaker` | Downstream **長期** 死 — 唔好嘥 thread 去等 |
| `@TimeLimiter` | 單 call **timeout** — 唔好等個 call 超過 X 秒 |
| `@Bulkhead` | **Concurrency cap** — 限同時多少個 in-flight call |

### Semaphore vs ThreadPool Bulkhead

| Type | How it works | Use case |
|---|---|---|
| **Semaphore** ⭐ | Counter — `maxConcurrentCalls` 個 permit，攞到先入 | Synchronous Spring MVC — 用緊 caller thread |
| **ThreadPool** | 獨立 thread pool — submit task 入個 queue | Reactive / async — 隔離 caller 同 downstream thread |

For cart-service (Servlet stack) — Semaphore 啱。

#### Config

```yaml
resilience4j:
  bulkhead:
    instances:
      productClient:
        max-concurrent-calls: 10       # 同時最多 10 個 in-flight
        max-wait-duration: 0           # 11th call 即時 reject (BulkheadFullException)
```

#### Code

```java
@CircuitBreaker(name = "productClient", fallbackMethod = "findByIdFallback")
@Retry(name = "productClient")
@Bulkhead(name = "productClient")    // ← 加 Bulkhead
public ProductSummary findById(Long userId, Long productId) {
    return productClient.findById(productId);
}
```

Aspect order matters again. Resilience4j default order from outer→inner:
```
@Retry → @CircuitBreaker → @RateLimiter → @TimeLimiter → @Bulkhead → method
```

Bulkhead 喺最內層 — 即係 attempt admission control **after** CB check + Retry decision. 我哋 L8 swap 咗 CB / Retry 順序，Bulkhead 仍然最內。

#### Chaos test pattern

```java
@Test
void bulkhead_limitsConcurrentCallsTo10() throws Exception {
    // Stub product-svc to be slow — every call takes 2 seconds
    wireMock.stubFor(get(urlPathEqualTo("/products/" + TEST_PRODUCT_ID))
            .willReturn(okJson(productJson()).withFixedDelay(2000)));
    stubInventoryHasStock(TEST_PRODUCT_ID, 1000);
    String authHeader = mockJwtFor(TEST_USER_ID);

    int totalRequests = 20;
    int parallelism = 20;
    ExecutorService pool = Executors.newFixedThreadPool(parallelism);
    CountDownLatch ready = new CountDownLatch(parallelism);
    CountDownLatch go = new CountDownLatch(1);

    AtomicInteger succeeded = new AtomicInteger();
    AtomicInteger bulkheadRejected = new AtomicInteger();

    for (int i = 0; i < totalRequests; i++) {
        pool.submit(() -> {
            try {
                ready.countDown();
                go.await();
                int status = mockMvc.perform(post("/cart/items")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                        .andReturn().getResponse().getStatus();
                if (status == 201) succeeded.incrementAndGet();
                else if (status == 503 || status == 429) bulkheadRejected.incrementAndGet();
            } catch (Exception e) { /* ignore */ }
        });
    }
    ready.await();   // wait until all threads ready
    go.countDown();  // fire simultaneously
    pool.shutdown();
    pool.awaitTermination(30, TimeUnit.SECONDS);

    // 10 fit in bulkhead, 10 rejected
    assertThat(succeeded.get()).isEqualTo(10);
    assertThat(bulkheadRejected.get()).isEqualTo(10);
}
```

#### Bulkhead vs TimeLimiter — combine 用最 powerful

```
@TimeLimiter alone:
  Slow call 等到 timeout → 釋放 thread → 下一個 call 入嚟 → 又等 timeout...
  ⚠️ 系統「健康」但 throughput 跌晒
  
@Bulkhead alone:
  限 10 個 concurrent call，但每個 call 可以等到天荒地老
  ⚠️ 10 個 thread 永遠 stuck

@TimeLimiter + @Bulkhead:
  10 個 concurrent 上限
  每個 call 必須 < 2s 完成
  ⭐ 兩條 dimension 都有 cap，maxThreadConsumption = 10, maxStuckTime = 2s
```

#### 🎯 Interview talking point

> "Bulkhead caps how many calls can be in-flight at once; TimeLimiter caps how long any single call can take. They protect different failure modes. A slow downstream without TimeLimiter ties up bulkhead permits indefinitely. A high-throughput downstream without Bulkhead can still exhaust the caller thread pool even if each call returns quickly. Production-grade resilience usually wires all three: CB for sustained failures, TimeLimiter for per-call latency cap, Bulkhead for concurrency cap."

---

### Q3 — Tuning thresholds for high-QPS vs low-QPS-critical

呢條 tuning 直覺需要 production 經驗。我畀你 mental model 同兩個 reference config。

#### Mental model — 三條 dimensions

| Knob | 高 traffic 時應該 | 低 traffic 時應該 |
|---|---|---|
| `sliding-window-size` | 大 (100-1000) — smooth out noise | 細 (10-30) — react 快啲 |
| `minimum-number-of-calls` | 大 (50-100) — 充份 samples | 細 (5-10) — 唔可以等太耐 |
| `failure-rate-threshold` | 中 (30-50%) — sensitive 啲，因為 downstream 影響大 | 低 (15-25%) — financial critical，唔可以容忍 |
| `slow-call-duration` | 行業 SLO (eg 200ms) | Lower (eg 500ms for payment) |

#### Config — 1000 QPS service (例如 product browse)

```yaml
resilience4j.circuitbreaker.instances.productBrowse:
  sliding-window-type: COUNT_BASED
  sliding-window-size: 100              # 0.1 秒 traffic = sufficient
  minimum-number-of-calls: 50           # 5% × 1000 QPS = 50 samples in 50ms
  failure-rate-threshold: 30            # 30% fail = clearly broken
  slow-call-rate-threshold: 30
  slow-call-duration-threshold: 200ms   # browse 應該 fast
  wait-duration-in-open-state: 10s      # 高 traffic 可以早啲 probe recovery
  permitted-number-of-calls-in-half-open-state: 10
```

**Rationale**: high QPS 即係統計樣本充份 — 可以用大 window 拉走 noise，trip 嘅 threshold 可以更靈敏（因為 false positive 嘅 cost 細，下一秒已經有新樣本確認）。

#### Config — 10 QPS critical service (例如 payment)

```yaml
resilience4j.circuitbreaker.instances.paymentProcessor:
  sliding-window-type: TIME_BASED       # ← switch to time-based 因為 traffic 太細
  sliding-window-size: 60               # 60 秒 window
  minimum-number-of-calls: 10           # 至少 10 個 sample 先 decide
  failure-rate-threshold: 20            # 20% fail = financial alert
  slow-call-rate-threshold: 20
  slow-call-duration-threshold: 500ms   # payment > 500ms = abnormal
  wait-duration-in-open-state: 60s      # 低 traffic 用長 cool-down 避免 yo-yo
  permitted-number-of-calls-in-half-open-state: 3
```

**Rationale**: low QPS = noise can hide signals — 需要更長 window + 更多 samples 才 trip。Financial 嘅 cost-of-failure 高，failure rate threshold 要低。COUNT_BASED 喺低 traffic 反應慢（要等夠 size 個 samples），TIME_BASED 用時間框界限。

#### Common trap — over-sensitive trip 嘅後果

```
sliding-window=10, min-calls=5, failure-rate=20%
→ 1 個 fail 入 5 samples = 20% → trip 即刻
→ 但其實 1/5 可能純粹 noise (network jitter / restart 中)
→ False positive trip → 30 秒 fast-fail → user 唔開心
```

對應 prevention：**`minimum-number-of-calls` 要至少 30-50%** of sliding window，等樣本數量真正 statistically meaningful。

#### 🎯 Interview talking point

> "Thresholds depend on traffic volume and risk tolerance. High-QPS services have plenty of statistical samples, so we can use a large window with a moderate failure-rate threshold and react fast. Low-QPS critical services like payment have less data but higher cost-of-failure — we use TIME_BASED windows, longer cool-down periods, and lower failure-rate thresholds. The trap with low traffic is false positive trips from random noise; setting minimum-number-of-calls to at least 30% of the window size keeps the statistic meaningful."

---

### Q4 — Prometheus scrape + PromQL alert

#### `prometheus.yml` scrape config

```yaml
scrape_configs:
  - job_name: 'cart-service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    scrape_timeout: 10s
    static_configs:
      - targets: ['cart-service:8084']
        labels:
          service: cart-service
          team: commerce
```

Spring Boot 嘅 actuator endpoint 唔係預設 expose Prometheus format — 要加埋 dep：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <scope>runtime</scope>
</dependency>
```

同 `application.yml` enable：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, circuitbreakers, prometheus
```

#### Resilience4j Prometheus metrics

關鍵 metric names (Resilience4j-Micrometer naming convention):

```
resilience4j_circuitbreaker_state{name="productClient", state="open"}    # 0 or 1
resilience4j_circuitbreaker_state{name="productClient", state="closed"}  # 0 or 1
resilience4j_circuitbreaker_state{name="productClient", state="half_open"}
resilience4j_circuitbreaker_calls_total{name="productClient", kind="successful"}
resilience4j_circuitbreaker_calls_total{name="productClient", kind="failed"}
resilience4j_circuitbreaker_calls_total{name="productClient", kind="not_permitted"}
resilience4j_circuitbreaker_failure_rate{name="productClient"}
resilience4j_circuitbreaker_slow_call_rate{name="productClient"}
```

`state` label 嘅值係 gauge — `1` 表示 active state，`0` 表示 not。所以 `state="open"` = 1 即係 CB 而家係 OPEN。

#### PromQL alert rule

```yaml
groups:
  - name: cart-service-resilience
    interval: 30s
    rules:
      - alert: ProductClientCircuitBreakerOpen
        # avg_over_time gives the fraction of the past 5min where CB was OPEN
        # > 0.5 = OPEN for more than half the window = persistent, not flap
        expr: |
          avg_over_time(
            resilience4j_circuitbreaker_state{name="productClient", state="open"}[5m]
          ) > 0.5
        for: 5m                         # require alert condition for 5 consecutive minutes
        labels:
          severity: warning
          team: commerce
          runbook: https://runbooks.internal/cart-cb-open
        annotations:
          summary: "cart-service productClient circuit breaker OPEN for 5+ minutes"
          description: |
            cart-service instance {{ $labels.instance }} has had its productClient
            circuit breaker in OPEN state for at least 5 minutes. This means product
            service has been failing or slow continuously. Users adding new items to
            cart will see 503 (no cached snapshot available).
            
            Check: 
              1. product-service health: kubectl -n commerce get pods -l app=product-service
              2. product-service error logs: kubectl logs -l app=product-service --tail=100
              3. Network: kubectl exec ... -- curl http://product-service:8082/actuator/health
```

#### Why `for: 5m` AND `avg_over_time(...)[5m] > 0.5`?

```
[5m] in expression: averages the metric over 5-minute lookback
for: 5m: requires the expression to be true for 5 consecutive minutes
```

兩個 layer 防 false positive：
- `avg_over_time > 0.5` filters out brief CB flap (e.g. CB trip → close → trip cycle)
- `for: 5m` 確保 trend 持續，避免 1 個 metric scrape glitch trigger

**Production rule**: alert SHOULD be `for: at least 2× scrape interval` — 否則一個 missed scrape 就會 false-fire alert。我哋 scrape interval 15s，所以 `for: 5m` 安全。

#### Additional alerts worth setting up

```promql
# Failure rate trending up (early warning)
- alert: ProductClientFailureRateRising
  expr: resilience4j_circuitbreaker_failure_rate{name="productClient"} > 0.3
  for: 2m

# Bulkhead rejecting calls (capacity issue)
- alert: ProductClientBulkheadFull
  expr: rate(resilience4j_bulkhead_available_concurrent_calls{name="productClient"}[1m]) == 0
  for: 1m

# Retry attempts elevated (transient errors widespread)
- alert: ProductClientRetryStorm
  expr: rate(resilience4j_retry_calls_total{name="productClient", kind="failed_with_retry"}[5m]) > 50
  for: 5m
```

#### 🎯 Interview talking point

> "We expose Resilience4j metrics through Spring Boot Actuator + Micrometer's Prometheus registry. The key metric is `resilience4j_circuitbreaker_state{state='open'}` — a gauge that's 1 when the CB is open. The PromQL alert uses `avg_over_time(...)[5m] > 0.5` to require sustained openness rather than alerting on transient flips, plus `for: 5m` for double protection against scrape glitches. Three other alerts I'd wire are failure-rate trending up as early warning, bulkhead capacity exhaustion, and retry storm detection."

---

### Q5 — Distributed CB state via Redis: design + trade-off

呢條係 staff-level architecture design question — production 真實要諗。

#### The problem restated

```
Instance 1   Instance 2   ...   Instance 10
   ↓             ↓                  ↓
            [product-service down]
   ↓             ↓                  ↓
CB trips     CB CLOSED         CB CLOSED
fast-fail    slow-fail         slow-fail
            (60s timeout)     (60s timeout)
```

Per-instance CB means **9/10 instances 仲喺度浪費 thread** until they each independently 累積足夠 failure samples to trip.

Worst case math: if traffic round-robins evenly, every instance needs sliding-window-size = 10 failed samples → 100 total slow-fail requests across the cluster before all 10 instances trip. 60 秒 × 100 = potential 6000 thread-seconds wasted.

#### Solution space

| Approach | How | Pros | Cons |
|---|---|---|---|
| **A. Redis-backed shared state** | Every CB check/record hits Redis | Single source of truth — 1 instance trips all | +1 Redis hop per call (1-2ms); Redis death = CB state unknown |
| **B. Sticky routing per downstream** | LB routes all product calls from cluster to a small pool of "gateway" instances | Gateway pool concentrates failures fast | Hot-spotting; gateway pool itself becomes SPOF |
| **C. Service mesh CB (Envoy)** | mTLS sidecar implements outlier detection at network layer | Application-level zero code; works across languages | Less granular than Resilience4j; mesh adoption cost |
| **D. Gossip-based propagation** | Instances broadcast CB trips to peers via gossip protocol | No central infrastructure; reasonably fast | Eventual consistency (slow propagation); complex impl |
| **E. Just live with per-instance CB** ⭐ | Accept that first 30-60s of outage has uneven CB state | Zero complexity; "good enough" for most cases | Some wasted threads during early outage |

#### Production reality — most teams pick E (per-instance)

Until you're operating at hyper-scale (1000s of replicas, millions QPS), the trade-off looks like:

```
Per-instance CB cost:
  Wasted thread-seconds during outage onset ≈ N × sliding-window × avg-call-latency
  For 10 instances × 10 calls × 60s = 6000 thread-seconds = 100 minutes
  ⚠️ Sounds bad but...

Shared-state CB cost:
  Latency tax: 1-2ms × QPS × 86400s/day
  For 100 QPS × 1ms × 86400 = 8640 seconds/day = 144 minutes/day permanent overhead
  PLUS Redis infrastructure cost + new failure mode (Redis down)
```

**One-time outage cost** vs **permanent latency tax** — per-instance usually wins.

#### When shared state IS worth it

3 scenarios:

1. **Long-tail outages** — if downstream regularly degrades for 10+ minutes, the wasted-thread cost compounds.
2. **Massive fan-out** — when 100+ instances call 1 downstream, per-instance "learning" wastes a lot of capacity.
3. **Cost-critical downstream** — if downstream is expensive per call (paid API, slow ML inference), every wasted retry costs money.

#### Sketch of Redis-backed CB (if you really need it)

Resilience4j doesn't ship this — you'd build a custom `CircuitBreakerRegistry`:

```java
@Component
public class RedisCircuitBreakerRegistry implements CircuitBreakerRegistry {

    private final StringRedisTemplate redis;
    private final CircuitBreakerRegistry localFallback;

    @Override
    public CircuitBreaker circuitBreaker(String name) {
        return CircuitBreaker.of(name, () -> {
            CircuitBreakerConfig cfg = configFromYaml(name);
            return new RedisAwareCircuitBreaker(name, cfg, redis);
        });
    }
}

class RedisAwareCircuitBreaker extends AbstractCircuitBreaker {

    @Override
    public boolean tryAcquirePermission() {
        // 1. Check Redis state (with local cache to reduce hops)
        String state = redis.opsForValue().get("cb:" + name + ":state");
        if ("OPEN".equals(state)) return false;
        return super.tryAcquirePermission();
    }

    @Override
    public void onError(...) {
        super.onError(...);
        // 2. Push state changes to Redis via pub/sub for peer notification
        if (getState() == OPEN) {
            redis.opsForValue().set("cb:" + name + ":state", "OPEN", Duration.ofSeconds(30));
            redis.convertAndSend("cb:transitions", "OPEN:" + name);
        }
    }
}
```

**Critical caveat**: Redis itself becomes a SPOF. You'd need a fail-open policy — if Redis is unreachable, **fall back to per-instance CB** rather than fail closed. Otherwise Redis outage =全 stack auth-style 404.

#### 🎯 Interview talking point

> "Per-instance circuit breaker is the default for a reason — it's zero-infrastructure and the only persistent overhead is the brief 'learning period' at the start of an outage. We chose to accept that cost rather than pay a 1-2ms Redis hop on every call. We'd reconsider for three scenarios: regular long-tail outages where wasted thread time compounds, massive fan-out where 100+ instances each need to learn independently, or expensive downstream calls where every retry costs real money. If we built a Redis-backed solution, the must-have is fail-open behavior on Redis outage — fall back to per-instance CB, never fail closed, otherwise Redis becomes a single point of failure for the entire resilience layer."

---

#### 🎯 Synthesis — Q1-Q5 之間嘅 link

| Question | Connects to L8 codebase |
|---|---|
| Q1 (InventoryClient CB) | 完成 cart-service 嘅 resilience coverage — L8 只覆蓋 ProductClient |
| Q2 (`@Bulkhead`) | 第 3 個 protection dimension — concurrency cap，補上 thread exhaustion 嘅最後缺口 |
| Q3 (Tuning) | 答 "config 點揀" 嘅問題 — L8 yaml 嘅 number 全部係 demo 用，prod tuning 要 scenario-specific |
| Q4 (Prometheus) | 將 L8 Phase 4 嘅 actuator endpoint 推上 industry-standard monitoring stack |
| Q5 (Distributed CB) | Scaling 角度 — L8 design assume single instance，多 replica 嘅 trade-off 要 staff-level analysis |

呢 5 條題目連起嚟 = **「Resilience4j 由 dev 落地 → production hardening」嘅 staff-level checklist**。L8 codebase 落地咗 ProductClient (CB + Retry + Fallback + Observability)，呢 5 條答案就係剩低 80% 嘅 production rigor — 完整 coverage、第 3 dimension protection、prod tuning judgement、monitoring + alerting、distributed CB trade-off。

</details>

---

## 12. Next Lesson Preview — Lesson 09

**L9 — Choreography Saga + Inventory Reservation (Strategy 2)**

- order-service scaffold (5th microservice)
- Distributed transaction across cart → inventory → payment via Kafka events
- Compensation flow when payment fails: release inventory reservation
- `inventory_reservation` table with TTL + state machine (from L7 hw Q4 deferred solution)
- Race condition analysis: atomic UPDATE with conditional `WHERE` clause
- Hooks into L8: `ResilientProductClient` pattern applied to all new clients

**Reinforce points** L8 leaves on the table:
- HW Q1 (InventoryClient CB) is the first hands-on in L9
- HW Q5 (distributed CB state) becomes relevant when L9 deploys multiple instances

---

## References

- Michael Nygard, *Release It!* (2007) — the original circuit breaker pattern
- Resilience4j docs: https://resilience4j.readme.io/
- AWS Architecture Blog — Exponential backoff and jitter: https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/
- Netflix Hystrix wiki (deprecated but historical context): https://github.com/Netflix/Hystrix/wiki/How-To-Use
- Martin Fowler — Circuit Breaker: https://martinfowler.com/bliki/CircuitBreaker.html
- Spring Boot 3 Actuator: https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html
- Micrometer Tracing: https://micrometer.io/docs/tracing
- Resilience4j Spring Boot 3 starter: https://github.com/resilience4j/resilience4j/tree/master/resilience4j-spring-boot3
