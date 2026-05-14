# Lesson 05 — Product Catalog Service (Snowflake ID + Cross-Service Event)

> **Goal**: Strangler Fig 嘅第二個 service extraction — 起 `product-service`，落地 application-layer **Snowflake ID generation**，published 第一個 **cross-service event** (`ProductCreatedEvent`)，重用 L4 嘅 outbox pattern。
>
> **Scope reminder**: 呢個 lesson **只係**落 product-service + write outbox row。 真係 publish 落 Kafka + 跨 service consume 留俾 L6。L5 嘅 deliverable 係「outbox row 寫對 + service 端到端 work」。

---

## Learning Objectives

到 L5 完，你應該答得到：

1. 點解 `Product` 同 `User` 必須喺唔同 bounded context — 從 read:write ratio、blast radius、compliance boundary、schema evolution velocity 4 個 axis
2. Snowflake ID 嘅 64-bit layout (sign / timestamp / DC / worker / sequence) 同 capacity math (4.2B IDs/sec global)
3. Snowflake 點 enable client-side ID generation 而 AUTO_INCREMENT 唔可以；點 simplify outbox pattern (ID 喺 INSERT 前已知)
4. Outbox pattern 跨 service reuse 嘅 trade-off — copy-paste (我哋揀) vs shared module (defer 到 L9)；rule-of-three justification
5. Cross-service event schema 嘅 5 個 invariants — eventId / eventVersion / occurredAt / payload minimization / encoding stability
6. JPA entity equality 嘅正確 idiom (`id != null && id.equals(...)`) — 點 handle transient vs managed state
7. JPA `ddl-auto: validate` 嘅 production ROI — 喺 startup 抓到 entity ↔ schema drift，防止 silent corruption

---

## 1. Project Setup — Multi-Service Local Dev

L4 開咗 user-service 之後，product-service 係**第二個** service。多 service local dev 嘅 infrastructure concerns 第一次浮面。

### Port management

每個 service 自己 spin up MySQL + Adminer container。Host port **必須 unique**，container port 永遠係 MySQL 嘅 default 3306。

```
Host port  →  Container port  →  Service
3306       →  n/a              →  Local system MySQL（小V 部機已有）
3307       →  3306             →  user-service-mysql
3308       →  3306             →  product-service-mysql
3309       →  3306             →  L7+ cart-service-mysql
```

⚠️ **Trap**：好多新人錯將 `3308:3307` (host:container)，以為 container port 都要 unique。實際 container 各自 network namespace，內部 port 隨便撞都唔事。Bug 出現喺 `localhost:3308` connect 到 container 嘅 3307，但 MySQL listen 緊 3306 → connection refused。**Rule**：host 揀，container 永遠 follow image default。

### Pom inheritance

Parent pom (`onlineshopping-microservices-parent`) 集中 manage:
- Spring Boot version + BOM
- 共用 dependency versions
- `<modules>` list 加 product-service

Service pom 只 declare:
- artifactId / name / description
- 直接 service-specific deps (JJWT version, testcontainers version)
- spring-boot-maven-plugin 嘅 packaging config

### Anti-pattern caught — service-to-service Maven dep

L5.5 落 outbox infra 時撞中：copy code 嘅時候 IDE 自動加 user-service 做 Maven dependency。結果 product-service classpath leak 咗 user-service 嘅 JAR — 包括 `V1__init_user_schema.sql`，撞 product-service 自己 V1 → Flyway throw `Found more than one migration with version 1`。

詳見 Section 9 嘅 war story。

---

## 2. Schema Design — Products Tri-Table

### Bounded context justification (4 axes)

Product vs User 唔可以同一 DB 嘅 reasons：

| Axis | Why |
|---|---|
| **Read:Write ratio** | Catalog QPS 比 user QPS 高 100x-1000x — shared connection pool 之下 product burst 會 starve user critical login flow |
| **Blast radius** | Product down = whole site dark, conversion funnel 即時 0；User down = anonymous browse 仲 work, 上半 funnel 仲 alive。SLO 要求差 1 個 nine |
| **Compliance boundary** | User = PII + GDPR, 嚴格 audit / encryption / retention；Product = no PII, no compliance burden — co-locate 會將 user 嘅 strict regime 強加落 product |
| **Schema evolution velocity** | Product team 每週加新 attribute (variants, A/B test fields)；user schema 一季加一個 column — migration cadence asymmetric 10x，coordination overhead 拖慢兩邊 |

### 3-table design

`products`：核心 entity，flat columns（唔做 EAV — see "Schema design decision" below）。
`categories`：tree structure，adjacency list (`parent_id` self-FK)。
`product_images`：1-to-many child of products，aggregate composition。

#### Schema design decision — flat columns vs EAV vs JSON

| Pattern | When to pick | Trade-off |
|---|---|---|
| **Flat columns** ← L5 揀 | < 1k product types, mostly homogeneous schema | Simple, type-safe, fast query；每個 attribute 都要 migration |
| EAV (Entity-Attribute-Value) | Marketplace-scale (淘寶, Amazon, eBay)，wildly diverse product types | Unlimited flexibility；join hell, type lost, hard to index |
| JSON column | Mid-scale, moderate diversity | Schema flex + single-row read；schema constraint 弱 |

**L5 揀 flat columns 嘅 reason**：呢個係 small-to-medium e-commerce，假設 catalog 全部 product 都係類似 attribute set。將來 hit product variants (e.g. T-shirt size × color combinations) 時，可以 incremental 升級個別 attribute 落 JSON column，唔需要 retroactive 落 EAV。

### Key invariants 一致 L4

- `BIGINT` (signed) for IDs — Snowflake 64-bit fits, no AUTO_INCREMENT
- Soft delete via `deleted_at TIMESTAMP(6) NULL`
- `@Version BIGINT NOT NULL DEFAULT 0` for optimistic locking
- Money 永遠 `BIGINT` cents 唔用 `DECIMAL` / `FLOAT`
- Enum 用 `VARCHAR` + `@Enumerated(EnumType.STRING)`，唔靠 ORDINAL
- Currency code 用 `CHAR(3)` (ISO 4217 always exactly 3 chars)

### FK delete semantics 區分

| Relationship | ON DELETE | Why |
|---|---|---|
| `categories.parent_id → categories.id` | `RESTRICT` | 防止意外刪 root 連累全 subtree |
| `products.category_id → categories.id` | `RESTRICT` | Category 有 product reference 唔可以 silently delete |
| `product_images.product_id → products.id` | `CASCADE` | Image 係 product 嘅 dependent，product 死圖跟住死 |

### Migration discipline — V3 audit-column fix

落 entity 時 JPA `ddl-auto: validate` 抓到 `categories` 同 `product_images` 缺 audit columns (V1 oversight)。**唔修 V1** — applied migration 係 immutable。Forward-only fix via **V3**：

```sql
ALTER TABLE categories ADD COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), ...
ALTER TABLE product_images ADD COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), ...
```

呢個習慣即使 local dev 都要 enforce — habit 喺平時，唔係 prod 出事先學。

---

## 3. Snowflake ID Generator

### 64-bit layout

```
| 1 bit | 41 bits         | 5 bits  | 5 bits   | 12 bits   |
| sign  | timestamp (ms)  | DC ID   | worker   | sequence  |
| 0     | from custom     | 0-31    | 0-31     | 0-4095    |
|       | epoch           |         |          |           |
```

- **Sign bit always 0**：Java `long` 係 signed，最高 bit = 0 → 保證 ID 正值
- **Timestamp 41 bits**：2^41 ms ≈ 69.7 years (從 custom epoch 起)
- **DC 5 bits**：32 datacenters max
- **Worker 5 bits**：32 workers per DC
- **Sequence 12 bits**：4096 IDs per millisecond per worker

### Capacity math

```
單 worker:   1ms × 4096 seq          = 4,096 IDs/ms = 4.096M IDs/sec
全 fleet:    32 DC × 32 worker × ... = 4.2 billion IDs/sec globally
```

**對任何 e-commerce over-provision 10 個 zero。**

### Custom epoch

```java
private static final long EPOCH = 1704067200000L;   // 2024-01-01 UTC
```

唔用 Unix epoch (1970) — 已耗 55 年，timestamp 剩 ~14 年 capacity。Custom epoch 從 service launch 起算，回到完整 69.7 年 buffer。

**Production rule**：epoch 一旦 deploy **永遠唔改** — 改咗 = 所有 stored ID 嘅 timestamp 解讀 break。

### Critical implementation traps

| Trap | Wrong | Right |
|---|---|---|
| Int overflow on bit shift | `1 << 5` (still int 32) | `1L << 5` (long) |
| Sequence rollover | `sequence % 4096` (slow) | `sequence & MAX_SEQUENCE` (bitwise AND) |
| Multi-thread race | unprotected | `synchronized` or `AtomicLong` CAS |
| Clock backwards | silently emit duplicate ID | throw `ClockMovedBackwardsException` |
| First call edge case | `lastTimestamp = 0` ambiguous | `lastTimestamp = -1L` 明確「未 generate 過」 |

### 4 個 senior interview must-know

| Q | Answer |
|---|---|
| 點解 sign bit 永遠 0？ | Java long signed，正值最高 bit 必 0；保證 cross-lang ID compatibility |
| 同一 ms 嘅 4097th request？ | Busy-wait 落下一 ms (`while(now == lastTs) now = currentTimeMillis()`)；sequence reset to 0 |
| Clock 倒退（NTP sync）？ | 拒絕 generate，throw exception。Production 通常 +50ms tolerance buffer，超 buffer 先 throw |
| 兩個 worker 撞 `(dc, worker)` pair？ | 集中分配 — Zookeeper / etcd / K8s StatefulSet ordinal，啟動時 claim unique pair，shutdown release |

### Snowflake vs AUTO_INCREMENT — 4 fundamental differences

| Property | AUTO_INCREMENT | Snowflake |
|---|---|---|
| **Distributed collision** | Sharding / multi-region 各自分 ID 必 collide | 64-bit layout 內嵌 worker_id, 0 coordination unique |
| **Throughput bottleneck** | High QPS INSERT serialize on auto-inc latch | Client-side 0 contention |
| **Idempotency via client-gen** | Impossible — DB owns ID space | Client 預先生成 ID + upsert pattern → natural idempotency |
| **Outbox event timing** | INSERT 後才知 ID，outbox payload build 喺 commit-time fight JPA lifecycle | ID 喺 INSERT 前已知，outbox row 同 entity 同 transaction atomic |

---

## 4. Outbox Pattern Reuse — Copy vs Shared Module

### The decision tree

當第二個 service (product-service) 都要 outbox pattern：

| Option | What | Trade-off |
|---|---|---|
| **Copy-paste** ← L5 揀 | 4 個 file (OutboxEvent + Repo + Service + Poller) 直接 copy，改 package | 短期 simple；長期 drift risk；明顯 duplication |
| Shared module `shared/outbox-pattern` | 抽 4 個 class 入 Maven module，兩個 service 同 depend | DRY；coupling + version dance；premature abstraction risk |
| External lib (Spring Modulith events, eventuate-tram) | Use prod-grade existing library | Production-mature；learning curve + magic + lock-in |

### Why L5 揀 copy-paste

**Rule of three** — pattern 重複 3 次先 abstract，唔好 speculate。我哋而家 2 次 (user + product) 仲未夠 informed。L9+ 預計有 5-6 service 用 outbox (inventory, order, payment, notification, ...) — 屆時 abstraction shape 已 emerge from 真實 use case。

**Documented trade-off**：copy-paste 嘅 drift risk 我哋 deliberately 接受。L9 一次性 promote 到 `shared/outbox-pattern` 嘅 refactor 屆時 plan。

### Senior interview punchline

> 「Incremental design — abstract by emergence，唔過早。Outbox 喺 user-service 落地時無 share；L5 product-service 加入時 2 次 use 仲未足夠 inform abstraction shape，所以再 copy 一次。落地 4-5 services 之後一次性 refactor — 因為到時 abstraction 嘅 shape 已 emerge from 真實 use case，唔需要 speculate。Rule of three（duplicate 3 次先 abstract）我揀更 conservative 嘅 4 次 — 多一輪 buffer。」

呢個係 **「mature engineering taste」** — 反對 premature DRY。

---

## 5. ProductCreatedEvent — Cross-Service Contract Design

### 5 critical invariants

呢個 record 一旦 publish，consumer 就 depend 上佢。**永久 contract**。Design 時要 think 5 axes：

| Axis | Why | 我哋 decision |
|---|---|---|
| **Event identity** | Consumer 點 dedup？網絡 retry 唔 produce ghost event？ | `eventId UUID` |
| **Event versioning** | Schema evolve 點 backward-compat？ | `eventVersion int`, start at 1; 新 field 加 nullable, break-change 用 v2 並行 |
| **Domain time** | Consumer 要知「事件幾時發生」唔係「乜時收到」 | `occurredAt Instant` |
| **Payload boundary** | Include 乜？Exclude 乜？ | Identity + stable attributes only — **冇** description, images, stockQuantity, deletedAt, version |
| **Encoding stability** | Cross-lang / parser JSON semantic 要一致 | record + Jackson default；money 永遠 `long cents` 唔 `BigDecimal` |

### Final shape

```java
public record ProductCreatedEvent(
    String eventId,           // UUID
    String eventType,         // "ProductCreated"
    int eventVersion,         // 1
    Instant occurredAt,
    
    Long productId,           // aggregate
    String name,
    String sku,
    Long priceCents,
    String currency,
    Long categoryId,
    String status
) {
    public static ProductCreatedEvent from(Product p) { ... }
}
```

### 3 anti-patterns to avoid

| Anti-pattern | 點解錯 |
|---|---|
| 將整個 Product entity serialize 入 event | Entity 包 internal fields (version, deletedAt, images)；coupling 升至 schema-level；entity refactor 一定 break consumer |
| `Map<String, Object>` payload | Loose typing；schema evolution 無法 audit |
| `BigDecimal` for money | JSON precision 跨 lang/parser 唔保證一致；用 long cents immutable cross-platform |

### Payload minimization principle

**「Event 應該係 self-contained + minimal」**。Consumer 需要更多嘢？兩個選擇：
1. **Eventual consistency via API** — call back product-service `GET /products/{id}` 攞 latest
2. **Multiple narrow events** — `ProductImagesUpdated`, `ProductPriceChanged` 各自 publish

事件**唔應該**做成「全 entity dump」嘅 push notification，咁就將 schema coupling 引入 cross-service boundary。

---

## 6. Service Layer — Atomic Snowflake + Save + Outbox

### Transaction flow

```java
@Transactional
public Product create(CreateProductRequest req) {
    // 1. Snowflake assign (application layer, NOT @GeneratedValue)
    Product p = Product.builder()
        .id(snowflake.nextId())                  // 喺 INSERT 之前
        .name(req.name())
        .sku(req.sku())
        // ...
        .build();
    
    // 2. Save entity
    Product saved = productRepo.save(p);
    
    // 3. Record outbox event (same transaction, MANDATORY enforcement)
    ProductCreatedEvent event = ProductCreatedEvent.from(saved);
    outboxService.record("ProductCreated", String.valueOf(saved.getId()), event);
    
    return saved;
}
```

### 3 critical traps

| Trap | Why |
|---|---|
| Method 上面冇 `@Transactional` | `OutboxService.record()` 嘅 `Propagation.MANDATORY` 即時 throw — fail fast prevent silent atomicity loss |
| Service pre-serialize 後 pass String 入 OutboxService | OutboxService 收 `Object payload` 內部會 `writeValueAsString` —— String 入 Object slot 觸發 double encoding。**Pass event object 直接，let OutboxService own serialization** |
| `findById` 唔加 `@Transactional(readOnly = true)` | Read 路徑 dirty-check 浪費；明示 readOnly 等 Hibernate skip 不必要 flush |

### Error path 嘅 contract

```java
.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
```

Service throw `ResponseStatusException` 係 **Spring shortcut**，trade-off vs domain-exception (Approach B)：
- Service 知 HTTP (less pure architecture)
- 但 code 少，single-protocol service 度 pragmatic
- L4 user-service 同樣選擇 — L5 consistency

**Future refactor candidate** (L9+)：若 product-service 加 gRPC API 或 CLI tool，promote 落 `ProductNotFoundException` domain exception + `@RestControllerAdvice` translate。

---

## 7. Endpoints + Validation

### `POST /products` 嘅 validation contract

```java
public record CreateProductRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 65535) String description,
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,64}$") String sku,
    @NotNull @PositiveOrZero Long priceCents,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
    @NotNull Long categoryId,
    @PositiveOrZero Integer initialStock
) {}
```

### Validation strictness — pragmatic vs purist

我哋 SKU regex 由原本 strict `^[A-Z0-9-]{3,64}$` 放寬到 mixed-case `^[A-Za-z0-9_-]{3,64}$`，理由：
- Real-world SKU (Apple, Sony, Etsy) 多數 mixed case
- 「uppercase only」係 enterprise PIM 嘅 internal convention，唔係 user-facing API 嘅 universal rule
- Pragmatic > purist

### Endpoint shape

```
POST  /products          → 201 Created + ProductResponse JSON
GET   /products/{id}     → 200 OK + ProductResponse / 404
POST  /categories        → 201 Created + CategoryResponse
GET   /categories        → 200 OK + List<CategoryResponse>
GET   /categories/{id}   → 200 OK / 404
```

### Response DTO discipline

`ProductResponse` 故意 exclude:
- `version` — internal optimistic-lock concern
- `deletedAt` — soft-delete metadata
- `updatedAt` — implementation detail

**Principle**：external API contract 由 client need 決定，唔係 entity dump。

---

## 8. Testing Strategy — Mock Boundaries Matter

### Pyramid ratio achieved (mirror L4)

- **Unit (Mockito)**: 4 tests — service layer behavior，mocked deps
- **Integration (Testcontainers)**: 5 tests — real MySQL 8.4，MockMvc HTTP layer
- **E2E**: 0 — out of L5 scope

### Critical insight — mock boundary 漏 cross-component bug

`create_writesOutboxEvent_withCorrectPayload` 嘅 unit test 用 ArgumentCaptor 捕住 ProductService **傳俾** OutboxService 嘅 String，captured value 係 single-encoded clean JSON → unit test 通過。

但 **integration test 度 DB 真實 query 出 stored payload** —— 顯示 double-encoded JSON (`"{\"eventId\":...\"}"`)。Bug 喺於 OutboxService 內部對 String 再做一次 `writeValueAsString`。

**Lesson**：Unit test 嘅 mock boundary 切咗 cross-component contract，可能 hide bugs。**Integration test 嘅唯一 value 就係穿過呢條 mock boundary**，verify actual runtime behavior。

### Senior testing taxonomy

| Test type | Scope | Cost | What it locks down |
|---|---|---|---|
| Unit (Mockito) | Single class, mocked deps | ~ms | Logic, sequence, exception propagation |
| Integration (Testcontainers) | Multiple classes + real DB | ~seconds | Schema mapping, transactions, cross-component contracts |
| E2E (full deploy) | Full system + network | ~minutes | Smoke / regression |

**Strict stubbing matters**：Mockito strict mode (default) 抓到 stub mismatch 即 throw，防止 silent fallback hide bug。我哋遇到嘅 `anyString()` vs `any()` mismatch 就係 strict mode 嘅 ROI。

### Test naming discipline

`findById_softDeletedProduct_throws404` 嘅 naming **暴露 HTTP coupling** — confusing。改 `_throwsResponseStatusException` 反映 actual service contract (throws specific type)，HTTP status 喺 assertion 度 verify 但**唔係 test 嘅 narrative subject**。Integration test 嘅 `_returns404` 先係真正 HTTP behavior test。

---

## 9. Bugs Hit — 9 War Stories (Interview Gold)

### Bug 1: Docker port mapping confusion (`3308:3307` not `3308:3306`)

**Symptom**: Application 連 DB → `Communications link failure` with 「0 milliseconds」 timing。

**Root cause**: Misunderstanding `host:container` semantic — container side 寫 unique port (3307) 以為要 avoid clash，但 MySQL image 內部固定 listen 3306。所以 `localhost:3308` → container:3307 → nothing listening → connection refused。

**Fix**: Container side 永遠跟 image default (`3308:3306`)。

**Lesson**: Container port = image-fixed; host port = unique-among-host-processes。

### Bug 2: MySQL 8.4 `mysql_native_password` plugin red herring

**Symptom**: `Plugin 'mysql_native_password' is not loaded`, error 1524.

**Initial hypothesis (wrong)**: docker-compose 嘅 legacy `--default-authentication-plugin=mysql_native_password` flag 喺 8.4 disabled。

**Actual root cause**: `mysql.user` table 入面冇 `product_service` user — env var `MYSQL_USER` 被 stale shell session 嘅 `user_service` override 咗。Volume 一旦 init 過唔重 init，所以後續 docker-compose change 無效。

**Lesson**: 
- Error message 唔係 root cause — server 用 default plugin handshake 處理 unknown user 嚟掩飾 user enumeration risk, 表面睇似 plugin issue 實則 user 唔存在
- MySQL Docker volume init 係 「first time only」semantic — 改 env vars 後要 `down -v` 強制重 init
- **Senior debug rule**：見「奇怪 auth error」第一動作係 `SELECT FROM mysql.user`，唔係 trust error message narrative

### Bug 3: JPA `ddl-auto: validate` 抓到 V1 missing audit columns

**Symptom**: Startup boom — `Schema-validation: missing column [created_at] in table [categories]`.

**Root cause**: V1 migration 寫漏 categories 同 product_images 嘅 audit columns，但 entity 全寫齊。`ddl-auto: validate` 喺 startup compare entity ↔ table 直接 reject boot。

**Fix**: **Forward-only V3 migration**，唔修 V1（即使 local dev 都要 enforce immutable migration discipline）。

**Lesson**: `validate` 嘅 production ROI proven — production deploy 度同樣 mismatch 會即時 startup fail，唔會 wait 到 query 度爆出 silent corruption。

### Bug 4: Cross-Service Maven Dependency (Distributed Monolith Smell)

**Symptom**: Flyway `Found more than one migration with version 1` —— offenders 一個喺 product-service 一個喺 `user-service-1.0.0-SNAPSHOT.jar`。

**Root cause**: product-service 嘅 `pom.xml` 加咗 user-service 做 compile dependency（猜想 IDE auto-suggest 引發）。User-service 嘅 JAR (連同 V1 migrations) 進入 product-service classpath。

**Why this is anti-pattern**:
- 違反 service autonomy — user-service deploy time 要 rebuild product-service
- Distributed monolith — 分散 deploy 但 build-time tightly coupled
- Classpath leak —— migrations, controllers, configurations 都 leak

**Fix**: Remove dep from pom.xml；copy outbox source 落 product-service `entity/` `repository/` `service/` (changes package declaration)。 

**Lesson**: **Microservices fundamental rule** — services do NOT depend on services。Communication 通過 stable contract (API / event)，唔係 source share。Shared module OK (但要 explicit cross-cutting concern，例如 `common-events`, `outbox-pattern`)。

### Bug 5: CHAR vs VARCHAR validation mismatch

**Symptom**: `wrong column type encountered in column [currency]; found [char] but expecting [varchar(3)]`.

**Root cause**: V1 schema 寫 `currency CHAR(3)`，但 JPA default String → VARCHAR。Hibernate validate 拒絕 boot。

**Fix**: Entity 加 `@JdbcTypeCode(SqlTypes.CHAR)` 告訴 Hibernate 對應 CHAR type — keep DB 嘅 CHAR(3) (ISO 4217 currency 永遠 3 char fixed-width)，唔降為 VARCHAR。

**Lesson**: JPA default Java↔JDBC mapping 大部分啱，但有 trap (Enum→ORDINAL, Instant→TIMESTAMP_WITH_TIMEZONE, String→VARCHAR)。新 entity 必須 audit 「default 啱唔啱合 schema intent」，唔係照 default ship。

### Bug 6: `-parameters` flag missing → `@PathVariable` reflection fail

**Symptom**: `GET /products/{id}` returns 500 — `Name for argument of type [java.lang.Long] not specified, and parameter name information not available via reflection`.

**Root cause**: `maven-compiler-plugin` 喺 service pom 度 override 咗（為 Lombok annotation processor 而 override），意外 override 走 Spring Boot starter parent 嘅 `<parameters>true</parameters>`。Java compiler 默認唔 emit parameter name metadata, Spring `@PathVariable` resolve 唔到 `id`。

**Fix**: Pom 加 `<parameters>true</parameters>` 喺 maven-compiler-plugin config 入面。

**Lesson**: Maven config inheritance 唔係 transparent merge — 每次 override 都要驗證 parent 嘅 settings 全部 explicit 保留。Spring magic 唔係 magic — 靠 bytecode metadata, 失去 metadata = magic 失效。

### Bug 7: Jackson `JavaTimeModule` missing in unit test

**Symptom**: Unit test 寫第一個就爆 `Java 8 date/time type java.time.Instant not supported by default`.

**Root cause**: Unit test 用 `new ObjectMapper()` instantiate fresh instance，**冇** Spring Boot auto-config 嘅 JavaTimeModule。Production 度個 ObjectMapper 由 `JacksonAutoConfiguration` 創建，已 register 一系列 module。

**Fix**: `new ObjectMapper().registerModule(new JavaTimeModule())`。

**Lesson**: Spring Boot magic 喺於 default config (JavaTimeModule, Jdk8Module, ParameterNamesModule, etc.)。Pure unit test 唔可以假設 production 嘅 behavior，要 manually mirror Spring 嘅 critical settings。

### Bug 8: Outbox payload double JSON encoding

**Symptom**: Integration test 抓到 stored payload `"{\"eventId\":...,\"eventType\":\"ProductCreated\",...}"` — 包住 JSON 嘅另一層 `"..."` + escaped inner quotes。**Unit test 全綠**但 integration test fail。

**Root cause**: `ProductService.create()` pre-serialize event 做 String，pass 入 `OutboxService.record(..., Object payload)`。OutboxService 內部對 Object 再 `writeValueAsString` — String 被視為 Object 重新 JSON-encode → double encoding。

**Why unit test missed**: ArgumentCaptor 捕住 ProductService **傳俾** OutboxService 嘅 String (pre-second-encode)，係 clean JSON。Mock 切咗 OutboxService 真正 behavior。

**Fix**: ProductService pass event object 直接（唔 pre-serialize），OutboxService own JSON serialization。同時 update unit test ArgumentCaptor type → `ProductCreatedEvent.class`。

**Lesson**: 
- **「Single owner of serialization」原則** — cross-layer 邊個 component own JSON 必須清晰
- `Object` parameter 嘅 API 表面 flexible 但 latent landmine — type-narrow parameter (例如 generic `<T extends DomainEvent>`) 可以 compile-time reject misuse
- **Mock boundary aware testing** — unit test 全綠 ≠ system work；integration test 至少要 verify DB 真實 stored critical fields

### Bug 9: Mockito strict stubbing mismatch after service signature change

**Symptom**: Test fail with `PotentialStubbingProblem` — stub 用 `anyString()`，actual call 第 3 arg 係 Event object。

**Root cause**: 修 Bug 8 之後，OutboxService 第 3 arg type 由 String → Object。但 unit test 嘅 stub matcher 仲係 `anyString()` —— Mockito strict mode 拒絕 silently fall back。

**Fix**: `anyString()` → `any()` 或 `any(ProductCreatedEvent.class)`。

**Lesson**: Mockito strict mode 嘅 ROI — 強迫 test author 喺 production refactor 後同步 update stubs。Silent fallback (lenient mode) 會 produce false-positive green test —— 最差嘅 test outcome。

---

## 10. Production Gaps Documented

L5 deliberately 跳咗以下 production concerns，記錄做 future refactor candidate：

| # | Gap | Severity | Defer to |
|---|---|---|---|
| 1 | **Snowflake worker_id hardcoded `1`** — multi-instance 必撞 | High (L8+ horizontal scale 前必修) | L8 inventory-service multi-instance scaling |
| 2 | **OutboxPoller @Scheduled 喺 test 度 spawn thread + 30s JVM hang** | Cosmetic | Fix branch `fix/outbox-poller-test-isolation`，user + product 一齊 |
| 3 | **Service-to-service Maven dep risk (Bug 4 fixed but pattern can recur)** | Architectural discipline | Pre-commit hook lint pom for cross-service deps |
| 4 | **Outbox pattern duplicate (copy-paste user → product)** | Code duplication | L9 promote `shared/outbox-pattern` |
| 5 | **product-service security 全 `permitAll`** — production 度 admin endpoints 要 JWT verify | Medium | L7 cart-service migration 設立 JWT propagation pattern |
| 6 | **No DELETE / PATCH endpoints** — 只 implement POST + GET | Feature scope | L6 / L7 補 |
| 7 | **Stock_quantity column 留喺 product table** — 將來抽出 inventory-service | Architectural debt | L8 inventory-service |
| 8 | **Docker compose env var `${VAR:-default}` shell drift risk** | Operational fragility | Cleanup PR — hard-code dev defaults |
| 9 | **user-service pom 都缺 `<parameters>true</parameters>`** — latent bug | Latent | Same cleanup PR with #8 |
| 10 | **Outbox payload double-encoding 嘅 same bug 可能存在喺 user-service** (Bug 8 嘅 sibling) | Latent — user-service tests 冇 verify raw payload | Audit + fix in cleanup PR |

---

## 11. Interview Prep / Resume Points

### 5 typical Q&A

**Q1: 點解 product-service 同 user-service 唔可以一個 Maven dep 引另一個？**

「違反 microservices 嘅 fundamental promise — independent deployment。Cross-service Maven dep = build-time coupled = 分散 deploy 但 monolith-shaped artifact dependency graph。Service B 升 version 強迫 A rebuild。Industry name = Distributed Monolith。Cross-service communication 必須通過 stable contract (HTTP API / event)，唔係 source share。Shared module OK 但要 explicit cross-cutting concern (例如 common-events)，唔可以 service-to-service。」

**Q2: Snowflake 嘅 datacenter_id 同 worker_id 點分配？Production 度點 handle 重啟？**

「集中分配。常見方案：Zookeeper sequential node, etcd lease, Kubernetes StatefulSet ordinal, dedicated PostgreSQL sequence。Service instance 啟動時 claim 一個唯一 `(dc, worker)` pair，shutdown 時 release。Production 度 worker_id 不可 hardcode — instance 一旦 scale > 32 撞中，所有 future ID 都 collide。重啟 reclaim 同一 worker_id 嘅 risk = clock backward 嘅變種 → 拒絕 generate 直到 last_timestamp + 1ms。」

**Q3: ProductCreatedEvent 嘅 schema 升 v2 嘅時候，舊 consumer 點處理？**

「Schema evolution 分 backward-compatible 同 break-change。Backward-compat 加 nullable field — 旧 consumer 用 null default 繼續 work。Break-change 嘅實踐做法 = parallel publish v1 + v2 一段時間 (e.g. 30 days)，cancellation 由 metric 確認所有 consumer 升 v2。Single-version event = brittle deployment chain。」

**Q4: 你嘅 outbox pattern 用 `@Transactional(propagation=MANDATORY)`，REQUIRED 唔可以？**

「REQUIRED 嘅 fail mode 係 silent — 如果 caller 冇 outer transaction，會 auto-create 新 transaction，silently break atomicity invariant（entity 失敗 rollback，但 outbox 已 commit 喺自己嘅 tx）。MANDATORY 喺 caller 冇 outer transaction 時即時 throw IllegalTransactionStateException — fail fast prevent 呢類 atomicity loss。Design-by-contract 喺 Spring 嘅實踐。」

**Q5: 你做 unit test 全綠但 integration test 度爆 double-encoding，當時點 debug？**

「ArgumentCaptor 喺 unit test 度捕住 ProductService 傳俾 OutboxService 嘅 String，係 clean JSON — 通過。Integration test 度 query DB 真實 stored payload，發現多咗 `"..."` 包住 + 內 escape — double encoded。Mock 切咗 OutboxService 嘅真正 behavior。Root cause = ProductService pre-serialize + OutboxService 內部又 serialize，兩層 都 own JSON。Lesson = serialization 必須 single owner；mock boundary 漏 cross-component bug；integration test 嘅唯一價值就係穿過呢條 mock boundary 驗證 actual runtime behavior。」

### Resume bullet candidates

- Extracted product catalog service from monolith using Strangler Fig pattern; built `Product / Category / ProductImage` tri-table schema with adjacency-list categories, soft-delete semantics, and `@Version` optimistic locking
- Implemented application-layer Snowflake ID generator (64-bit: 41-bit timestamp + 5-bit DC + 5-bit worker + 12-bit sequence) replacing AUTO_INCREMENT; supports 4.2B IDs/sec globally with 0 coordination
- Designed cross-service `ProductCreatedEvent` schema with explicit `eventId / eventVersion / occurredAt` metadata and payload minimization (identity + stable attributes only); reused L4 outbox pattern via `@Transactional(propagation=MANDATORY)` invariant enforcement
- Caught and fixed Distributed Monolith anti-pattern via Flyway "multiple V1" symptom — diagnosed cross-service Maven dependency leaking another service's classpath including migrations
- 9-test green strategy (Mockito unit + Testcontainers integration) covering atomic Snowflake assignment, outbox payload verification, validation failures, and soft-delete semantics; identified mock-boundary blind spot via double JSON encoding incident

---

## 12. Homework / Reflection

完 lesson 之前自問（解答喺 L6 開始時 fold 入 collapsible block）：

1. 你 `OutboxPoller` 用 `@Scheduled(fixedDelay = 1000)`。如果 L8 product-service horizontal scale 到 5 個 instance，每個 instance 個 poller 都 fire — 5 個 poller 同時撈 pending outbox rows，會點？提示：concurrent `SELECT ... WHERE published_at IS NULL` 嘅 race condition。寫出 SQL-level fix。
2. `ProductCreatedEvent.eventVersion` 而家 hardcode `1`。如果你下個月要加 `discountedPriceCents Long` (nullable) — 升 v2？保持 v1？解釋你嘅 decision 嘅 backward-compat 含意。如果再下個月要 break-change `priceCents` 由 cents 變 BigDecimal，consumer 升級嘅 rollout plan 點寫？
3. 你 `Product.categoryId` 用 `Long` 唔用 `@ManyToOne Category`。L9 將來如果 Category 抽出獨立 service，呢個 plain Long reference 嘅 implication 點？如果係 `@ManyToOne` reference 又會點？
4. Snowflake `worker_id` 而家 hardcode `1`。**寫 pseudo-code 演示**用 Zookeeper sequential node 落地動態分配 worker_id 嘅流程（claim → use → release on shutdown）。如果 service crash 唔 release 點？
5. `ProductService.findById()` throw `ResponseStatusException(NOT_FOUND)` 係 Spring shortcut (Approach A)。如果你下個月要支援 gRPC 同 CLI 兩個 protocol，你會 refactor 落 Approach B (domain exception + `@RestControllerAdvice`)？解釋 refactor steps + 點 keep HTTP API contract 不變。

<details>
<summary><strong>📖 Polished Solutions (L6 session fold-back)</strong></summary>

> 小V 嘅 cold-attempt notes:
> - **Q2**: 「應該加 version」— 啱方向，但要 unpack additive vs breaking
> - **Q3**: 「拆開 join 會 break」— 啱方向，但 actual 答案反直覺
> - **Q5**: 「中央處理 exception 嘅角色」— 答中核心
> - **Q1 / Q4**: cold — 全新 concept

---

### Q1 — Outbox horizontal-scale race + SQL-level fix

**Problem：** 5 個 product-service instance 同時跑，每個 instance 嘅 `OutboxPoller` `@Scheduled(fixedDelay = 1000)` fire 一次：

```sql
SELECT * FROM outbox_events WHERE published_at IS NULL LIMIT 100;
```

5 個 poller 都撈到**同一批 rows** → 每個都嘗試 publish → Kafka 收到**重複 5 次** → 即使 consumer side idempotent dedupe，都係 5x 流量浪費咗，仲未計 publish 失敗 / partial commit 嘅 edge case。

**Fix — `FOR UPDATE SKIP LOCKED` (MySQL 8 / PostgreSQL 9.5+)：**

```sql
SELECT * FROM outbox_events
WHERE published_at IS NULL
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

**機制：**
- `FOR UPDATE` → 對 SELECT 返嘅 rows 攞 **row-level exclusive lock**，要 commit / rollback 先 release
- `SKIP LOCKED` → 如果其他 transaction 已 lock 咗呢啲 row，**跳過 (唔等)**，撈下一批未 locked 嘅

**多 instance 嘅 timeline：**
```
T=0  Instance A: SELECT FOR UPDATE SKIP LOCKED → 撈 row 1-100，lock 住
T=0  Instance B: SELECT FOR UPDATE SKIP LOCKED → row 1-100 已 locked → 跳過 → 撈 row 101-200
T=0  Instance C: SELECT FOR UPDATE SKIP LOCKED → 1-200 locked → 撈 201-300
```

**對比唔加 `SKIP LOCKED`：** 其他 instance 會 **block + wait** 直到 A commit — DB connection pool exhausted (呢個 root cause flavor 同 FDR DB story 同源)。

**Spring 落地：**
```java
@Query(value = """
    SELECT * FROM outbox_events
    WHERE published_at IS NULL
    ORDER BY id
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
List<OutboxEvent> findPendingForUpdate(@Param("limit") int limit);
```

呼叫嘅 method 必須 `@Transactional`，否則 lock 即時 release 無意義。

---

### Q2 — Event versioning rollout

**Sub-Q2a — 加 `discountedPriceCents Long` (nullable)：唔使升 version**

**前提：** consumer 用 `@JsonIgnoreProperties(ignoreUnknown = true)` 或 Jackson default forgiving mode。

**Backward-compat reasoning：**
- 舊 consumer 嘅 DTO 無 `discountedPriceCents` field → Jackson 靜默 drop unknown field
- 新 consumer 嘅 DTO 有 field → 收到 v1 event (無 field) → 自動 default 為 `null`
- Producer 升咗有時加 / 有時無 — consumer 兩邊都 OK

**Rule of thumb：**
| Change | Compatible? | Version bump? |
|---|---|---|
| Adding optional field | ✅ | No |
| Removing field | ❌ | Yes |
| Renaming field | ❌ | Yes |
| Changing field type | ❌ | Yes |
| Changing field semantic (e.g. cents → dollars) | ❌ (silent corruption!) | Yes |

**Sub-Q2b — `priceCents Long` → `price BigDecimal`：必須升 v2 + dual-publish rollout**

**點解 dual-publish？** 你 cannot:
- 一晚 deploy 全部 consumer + producer — N 個 service 散晒，blast radius 太大
- 只升 producer — 舊 consumer deserialize `BigDecimal` 入 `Long` field → 即時爆
- 只升 consumer — producer 仲未出新 format → consumer wait 到天荒地老

**Rollout plan (safe deprecation window)：**

```
Phase 1 (Week 1) — Producer 升級
   Producer 同時 publish v1 + v2 兩個 event：
   - Topic `product-events-v1` (legacy format, priceCents Long)
   - Topic `product-events-v2` (new format, price BigDecimal)
   Consumer 仲淨 subscribe v1 → 無感覺

Phase 2 (Week 2-3) — Consumer 逐個 migrate
   Consumer A → cut over 去 v2
   Consumer B → cut over 去 v2
   Consumer C → cut over 去 v2
   (per-service rollback 容易，rollback 只係單一 consumer 切返 v1)

Phase 3 (Week 4) — Sunset v1
   驗證所有 consumer 都 on v2 (查 v1 topic consumer group lag = 0)
   → Producer 停發 v1
   → 刪 v1 topic
```

**真實世界類比：** gRPC `proto2 → proto3`、Kafka itself 嘅 message version byte、Slack API v1 → v2。

---

### Q3 — `Long categoryId` vs `@ManyToOne Category` — 反直覺結論

當 monolith 之內，兩個 design 嘅 surface API 睇落差唔多。但**為未來 service split 留後路**嘅角度，差好遠：

| 維度 | `Long categoryId` (而家嘅 design) | `@ManyToOne Category category` |
|---|---|---|
| **DB FK constraint** | 無 — already loose coupling | 有 — cut service 之前要 drop FK |
| **JPA fetch behavior** | 已經 manual：`categoryRepo.findById(p.getCategoryId())` | Auto lazy load：`product.getCategory().getName()` |
| **Business code 散佈** | Fetch site 集中 (你被迫 explicit) | 散晒喺 service / template / DTO mapper — 寫嗰陣覺得方便，refactor 嗰陣痛苦 |
| **L9 cut Category service 嘅 migration scope** | **細**：將 `categoryRepo.findById` 換做 `categoryClient.fetch(id)` (REST / gRPC) | **大**：drop FK + grep `getCategory()` 全部 callsites + 加 client + handle network failure / timeout / retry / fallback |
| **Bonus capability** | 加 `categoryNameSnapshot String` field 唔再 query 都得 | 想 snapshot 都難，JPA 強迫你 live join |

**Takeaway：**
- `Long categoryId` 唔係懶嘅 design — 係**有意識嘅 cross-aggregate boundary discipline**
- `@ManyToOne` 喺 monolith 內舒服，但 L9 cut service 嗰陣痛
- 呢個係 "**preparing the monolith for split**" 嘅 Strangler Fig 配套技巧 — 即使未 cut service，aggregate boundary 寫法已經要 service-ready

**唔代表 `@ManyToOne` 永遠唔好：** 同一 aggregate 內 (e.g. `Product ↔ ProductImage` intra-aggregate) 用 `@ManyToOne` / `@OneToMany` 啱嘅。Rule：**aggregate 內 reference 用 JPA association；aggregate 之間 (尤其未來可能 cut service 嗰啲) 用 plain ID。**

---

### Q4 — Zookeeper ephemeral sequential allocation

**Mental model：**
- ZK = distributed coordination service (key-value store with watches)
- **Ephemeral node** = session 死 (process crash / network drop / explicit close) → node 自動消失
- **Sequential node** = ZK 自動 append monotonic counter suffix

**Pseudo-code：**

```java
public class SnowflakeWorkerIdAllocator {
    private static final int WORKER_ID_BITS = 5;
    private static final int MAX_WORKERS = 1 << WORKER_ID_BITS;  // 32
    private ZooKeeper zk;
    private String myPath;
    private int workerId;

    @PostConstruct
    public int allocate() throws Exception {
        // 1. CONNECT — establish ZK session
        zk = new ZooKeeper("zk-cluster:2181", SESSION_TIMEOUT_MS, sessionWatcher);

        // 2. CLAIM — create ephemeral sequential node under /snowflake/workers/
        myPath = zk.create(
            "/snowflake/workers/worker-",
            getHostname().getBytes(StandardCharsets.UTF_8),  // metadata: who owns this slot
            ZooDefs.Ids.OPEN_ACL_UNSAFE,
            CreateMode.EPHEMERAL_SEQUENTIAL
        );
        // myPath e.g. "/snowflake/workers/worker-0000000007"

        // 3. USE — extract suffix → mod 32 → worker_id
        int sequenceNum = parseSuffix(myPath);  // 7
        workerId = sequenceNum % MAX_WORKERS;   // 7 % 32 = 7

        // 4. SAFETY — 過度 claim 防 saturation
        if (countActiveWorkers() > MAX_WORKERS) {
            zk.delete(myPath, -1);
            throw new IllegalStateException("Snowflake worker slots exhausted (>32 active)");
        }

        log.info("Allocated worker_id={} via path={}", workerId, myPath);
        return workerId;
    }

    // 5. RELEASE — graceful shutdown
    @PreDestroy
    public void shutdown() {
        zk.close();  // ZK session close → ephemeral node 即時消失 → slot 釋放
    }

    // 6. CRASH HANDLING — 乜都唔使做!
    //    Process die → ZK 偵測 session timeout (typically 10-30s)
    //    → ZK 自動刪 ephemeral node → slot 自動 release → 下個 instance claim 新 sequential 號
}
```

**Crash 嘅關鍵：**
> **你乜都唔使寫。** Ephemeral 個 promise 就係 session 死 = node 死。Process crash / kill -9 / hardware failure 全部 work。

**Edge case 必須注意：**

1. **Sequential counter overflow** — ZK sequential 係 monotonic increasing int，數字一路升 (7 → 1007 → 1000007)，所以**一定要 `% 32`** 才落 worker_id。
2. **Split-brain (network partition / GC pause > session timeout)** — 你個 ZK session expire 咗，但 process 仲生 (e.g. STW GC pause 60s)。ZK 已刪你個 ephemeral node → 另一個 instance 起身 claim 咗你個 sequence slot → 兩個 instance 同一個 worker_id → **Snowflake duplicate ID 災難**。
   **Mitigation：** 喺 process 入面 register session watcher，收到 `KeeperState.Expired` 即時 **panic + exit** — 唔好繼續發 ID。靠 K8s / supervisor 重啟拎新 slot。
3. **ZK cluster 自己死晒** — Snowflake generation 停 (fail closed) > 繼續發可能 duplicate 嘅 ID (fail open)。

---

### Q5 — Approach A (Spring shortcut) → Approach B (domain exception + advice) refactor

**Approach A (現狀):**
```java
public Product findById(Long id) {
    return repo.findById(id)
        .filter(p -> p.getDeletedAt() == null)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
}
```
`ResponseStatusException` 嚟自 `org.springframework.web.server` — **綁死 HTTP semantics**，service layer 提早 commit 咗 HTTP contract。

**Approach B (refactored):**

**Step 1 — 加 domain exception (純 domain，唔知 HTTP)**
```java
package com.onlineshopping.product.exception;

public class ProductNotFoundException extends RuntimeException {
    private final Long productId;

    public ProductNotFoundException(Long id) {
        super("Product " + id + " not found");
        this.productId = id;
    }

    public Long getProductId() { return productId; }
}
```

**Step 2 — Service throw domain exception**
```java
public Product findById(Long id) {
    return repo.findById(id)
        .filter(p -> p.getDeletedAt() == null)
        .orElseThrow(() -> new ProductNotFoundException(id));
}
```
Service layer 而家 **protocol-agnostic** — 唔 import 任何 `org.springframework.web` 嘅嘢。

**Step 3 — HTTP 層 translate (RestControllerAdvice)**
```java
@RestControllerAdvice
public class ProductExceptionHandler {
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> handle(ProductNotFoundException e) {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiError.of("PRODUCT_NOT_FOUND", e.getMessage()));
    }
}
```

**Step 4 — Integration test verify HTTP contract unchanged**
- Status code 仍然 `404`
- Response body shape 仍然 `{"error": "...", "code": "..."}`
- 任何 existing client 唔需要改

**Multi-protocol payoff：**

```java
// gRPC interceptor — 同一 domain exception，translate 成 gRPC Status
public class GrpcExceptionInterceptor implements ServerInterceptor {
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(...) {
        try {
            return next.startCall(call, headers);
        } catch (ProductNotFoundException e) {
            call.close(Status.NOT_FOUND.withDescription(e.getMessage()), new Metadata());
        }
    }
}

// CLI — 直接 catch domain exception，friendly message
try {
    Product p = productService.findById(id);
    System.out.println(p);
} catch (ProductNotFoundException e) {
    System.err.println("[error] " + e.getMessage());
    System.exit(1);
}
```

**Approach B 嘅成本：** 多兩個 class (domain exception + advice)。**收益：**
- 多 protocol 各自 translate 同一個 domain exception，零 service layer 改動
- Domain exception 帶 structured data (`productId`)，HTTP / gRPC / CLI 可以各自 format
- Service layer 更易 unit test — 唔再 mock HTTP infra

**Rule of thumb：**
- 1 protocol (HTTP only) + 唔會擴展 → Approach A 夠
- ≥ 2 protocol OR domain exception 帶 metadata OR aggressively unit-test service layer → Approach B

</details>

---

## 13. 下一步 — Lesson 06 預告

**L6 — Cross-Service Event Consumption via Kafka**

- 起 local Kafka container（docker-compose 加 Kafka + Zookeeper）
- `product-service` 嘅 `OutboxPoller` 由 「log to console」變 「publish 到 Kafka topic `product-events`」
- 起一個 minimal `inventory-service` (skeleton + Kafka consumer)，subscribe `product-events`
- 收到 `ProductCreatedEvent` 創建 inventory row (`product_id, stock_quantity` 初始化 0)
- 第一次真正驗證 cross-service event-driven communication
- Branch: `lesson-06-kafka-cross-service`
- Deliverable: 兩個 service 跑緊 + POST /products 之後 inventory row 自動出現
- 重點 concept: **at-least-once delivery semantics**, **idempotent consumer**, **dead letter queue**, **consumer lag monitoring**

L7 之後 cart-service 嘅 JWT propagation；L8 inventory split + Snowflake worker_id dynamic allocation；L9 outbox `shared/` module promotion。

---

## References

- Twitter Snowflake (original Scala): https://github.com/twitter-archive/snowflake
- Vlad Mihalcea, *The best way to implement equals, hashCode, and toString with JPA entities*: https://vladmihalcea.com/the-best-way-to-implement-equals-hashcode-and-tostring-with-jpa-and-hibernate/
- Martin Fowler, *Distributed Monolith*: https://www.thoughtworks.com/insights/articles/microservices-need-architects
- Microservices.io, *Transactional Outbox*: https://microservices.io/patterns/data/transactional-outbox.html
- RFC 4122 UUID (eventId): https://www.rfc-editor.org/rfc/rfc4122
- ISO 4217 Currency Codes: https://www.iso.org/iso-4217-currency-codes.html
- Spring Framework `ResponseStatusException` docs: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/exceptions.html
