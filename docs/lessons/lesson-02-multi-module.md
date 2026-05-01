# Lesson 02 — Multi-Module Maven + Shared Library Trap

> **Goal**: 起 parent POM + shared modules + 1 placeholder service module，prove build chain 行得通。識別「shared domain model」反 pattern。掌握 BOM 兩種食法 trade-off。
>
> **Deliverable**:
> - Root `pom.xml`（aggregator + parent + BOM import）
> - `shared/common-events`（含 `DomainEvent` interface）
> - `shared/common-dto`（含 `ApiResponse<T>` envelope）
> - `services/user-service`（跑得起嘅 Spring Boot placeholder + `/health` endpoint）
> - Maven Wrapper（reproducible builds）
>
> **Hands-on weight**: 中（純 Maven config + 4 個 file）。重點係**搞清楚每個 tag 點解擺嗰度**，唔係抄 template。

---

## Learning Objectives

完 lesson 之後你應該識：

1. 講得出 Maven **Aggregation (`<modules>`)** 同 **Inheritance (`<parent>`)** 嘅分別 + 點解 orthogonal
2. 講得出 `<dependencyManagement>` 同 `<dependencies>` 嘅分別（vending machine model）
3. 解釋 BOM (Bill of Materials) 點 work + Way 1 (`spring-boot-starter-parent` inherit) vs Way 2 (BOM import) 嘅 trade-off
4. 答得出 Maven 3-step parent resolution chain + 點解第一次 build 特別易撞 `<relativePath>` 問題
5. 識別 microservices 嘅「shared library trap」 — 邊啲 jar OK 共享、邊啲共享即等於 distributed monolith
6. 揀啱 distributed ID strategy（UUID v4 / UUID v7 / Snowflake）by use case
7. 用 Maven Wrapper 保證 reproducible build

---

## 1. Repo Layout

L2 完成後嘅樹：

```
OnlineShopping-Microservices/
├── pom.xml                                    ← root: parent + aggregator + BOM
├── mvnw, mvnw.cmd                             ← Maven Wrapper script
├── .mvn/wrapper/maven-wrapper.properties      ← pin Maven version
├── shared/
│   ├── common-events/
│   │   ├── pom.xml
│   │   └── src/main/java/com/onlineshopping/events/DomainEvent.java
│   └── common-dto/
│       ├── pom.xml
│       └── src/main/java/com/onlineshopping/dto/ApiResponse.java
└── services/
    └── user-service/
        ├── pom.xml
        └── src/main/java/com/onlineshopping/user/
            ├── UserServiceApplication.java
            └── HealthController.java
```

之後 lessons 會加埋 `product-service` / `inventory-service` / `cart-service` / `order-service` / `payment-service` / `notification-service` / `api-gateway`，但 L2 只 set 起 1 個 service module 試 build chain。

---

## 2. Root POM 嘅 8 個 Section

```xml
<project>
  <modelVersion>4.0.0</modelVersion>                 <!-- ① boilerplate -->

  <groupId>com.onlineshopping</groupId>              <!-- ② project coordinates -->
  <artifactId>onlineshopping-microservices-parent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>                         <!-- 一定 pom，aggregator/parent 唔編譯 -->

  <properties>                                       <!-- ③ shared variables -->
    <java.version>21</java.version>
    <spring-boot.version>3.5.14</spring-boot.version>
    ...
  </properties>

  <modules>                                          <!-- ④ aggregation list -->
    <module>shared/common-events</module>
    <module>shared/common-dto</module>
    <module>services/user-service</module>
  </modules>

  <dependencyManagement>                             <!-- ⑤ inheritance + BOM -->
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      ...                                            <!-- 自己 shared modules 嘅 GAV declare -->
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>                               <!-- ⑥ plugin versions pinned -->
      <plugins>
        <plugin>...spring-boot-maven-plugin...</plugin>
        <plugin>...maven-compiler-plugin...3.15.0...</plugin>
        <plugin>...maven-surefire-plugin...3.5.5...</plugin>
        <plugin>...maven-jar-plugin...3.5.0...</plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

每個 section 嘅深入解釋見下面 §3-§6。

---

## 3. Aggregation vs Inheritance — 兩件唔同嘅嘢

新人最常混淆嘅 conceptual pitfall。

| 關係 | 由邊個聲明 | 影響 | Cardinality |
|------|-----------|------|-------------|
| **Aggregation** | **Parent** 喺 `<modules>` listed children | Build order：root `mvn install` 帶起 children | 1-to-many |
| **Inheritance** | **Child** 喺 `<parent>` 指返 parent | Config 繼承：properties / dependencyManagement / build | 1-to-1（child 只能有 1 parent）|

### 證明兩者 orthogonal

| Scenario | Root build 帶起 child? | Child 繼承 root config? |
|----------|-----------------------|--------------------------|
| Root listed in `<modules>`，Child 寫 `<parent>` | ✅ | ✅ |
| Root listed in `<modules>`，Child 唔寫 `<parent>` | ✅ build 起 | ❌ 唔繼承 |
| Root **冇** list，Child 寫 `<parent>` | ❌ root build 唔帶起 | ✅ 單獨 build child 仍繼承 |
| 都唔 declare | ❌ | ❌ — 兩個獨立 project |

99% 真實項目兩個一齊 use（如我哋呢個項目），但要分得清。

### 真實 use case for orthogonality（advanced）

- **Partial CI build**: `mvn -pl services/user-service -am install` — 只 build user-service + 它依賴嘅 modules
- **Excluded experimental module**: feature branch 仲未穩 → root 暫時 `<modules>` 唔 list，但 child 自己保留 `<parent>` 可獨立 build
- **Reactor build order**: Maven 依**inter-module dependency graph** topological sort，唔係照 `<modules>` 嗰個 order

---

## 4. `<dependencyManagement>` vs `<dependencies>` — Vending Machine Model

| 寫法 | Cascade 落 child? | Child 要不要 declare? |
|------|---------------------|--------------------------|
| `<dependencies>` 喺 parent | ✅ 自動全部繼承 | 唔使（自動拎到）|
| `<dependencyManagement>` 喺 parent | ❌ 只 declare version + scope | 要！但唔使寫 version |

**Mental model**: `<dependencyManagement>` 係 **"version vending machine"** — child 唔自己帶 version 由 parent 派；child 自己帶 version 就用自己嘅（通常係反 pattern，避免）。

### 點樣對 microservices 啱用

8 個 service 唔係個個都用 lombok / web / kafka / redis。
- 用 `<dependencies>` 喺 root → 全 8 個 service 強制有 lombok（即使 notification-service 唔需要）→ jar size 同 transitive risk 增加
- 用 `<dependencyManagement>` → service 自己 declare 想要嘅 dep（**唔寫 version**），唔想嘅唔寫，乾淨

---

## 5. BOM (Bill of Materials) — 兩種食法 + Trade-off

### 咩係 BOM

一個 pom file，`<packaging>pom</packaging>`，**淨係**裝住一大段 `<dependencyManagement>` declare 一籮 lib 嘅 curated version，自己唔包任何 dependency。

Spring Boot 出兩件嘢：
- **`spring-boot-dependencies`** ← 純 BOM，only versions（200+ libs：jackson, hibernate, tomcat, micrometer, kafka client...）
- **`spring-boot-starter-parent`** ← BOM + plugin pinning + Java config + 一籮 default

### Way 1: Inherit `spring-boot-starter-parent`（你 monolith 用嘅）

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.14</version>
</parent>
```

| Pro | Con |
|-----|-----|
| 一行食晒 versions + plugins + Java config | **`<parent>` slot 用咗** — 你冇得再 inherit 自己 root |
| 適合 single-module 項目 | ❌ Multi-module microservices 死路 |

### Way 2: Import BOM via `<dependencyManagement>`（我哋揀呢個）

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type>           <!-- 重點：import 一個 pom -->
      <scope>import</scope>      <!-- 重點：scope=import 觸發 BOM 機制 -->
    </dependency>
  </dependencies>
</dependencyManagement>
```

| Pro | Con |
|-----|-----|
| `<parent>` slot 留返畀自己 root pom | 自己要 pin maven-compiler-plugin / surefire / jar plugin（fixed cost）|
| Multi-module 暢通 | 多寫 5-10 行 `<pluginManagement>` |

### 我哋 microservices 嘅繼承鏈

```
spring-boot-dependencies (BOM by Spring team)
        ↓ <scope>import</scope>
我哋 root pom            ← + 我哋自己 properties / modules / plugin pinning
        ↓ <parent>
shared/common-events
shared/common-dto
services/user-service
```

Child 寫 `<dependency>spring-boot-starter-web</dependency>` 唔使寫 version — 透過 `<dependencyManagement>` chain 自動拎 3.5.14 嗰套 curated version。

### Way 2 嘅 fixed cost：你會親身撞到 plugin warning

第一次 build 你會見：
```
[WARNING] Ignoring incompatible plugin version 4.0.0-beta-4:
maven-compiler-plugin requires Maven 4.0.0-rc-4
```

原因：BOM **只管 dependency 唔管 plugin**。如果 root pom 嘅 `<pluginManagement>` 唔 pin plugin version，Maven 自動拎 latest（撞到 4.0 beta）→ 同你 Maven 3.9 runtime 衝突。

修正：root pom 顯式 pin
```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.15.0</version>
</plugin>
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <version>3.5.5</version>
</plugin>
<plugin>
  <artifactId>maven-jar-plugin</artifactId>
  <version>3.5.0</version>
</plugin>
```

### Interview takeaway

> **Q: Why did you use BOM import instead of `spring-boot-starter-parent`?**
>
> "Multi-module project — each child module's `<parent>` slot must point to our own aggregator POM, not to a third-party. BOM import via `<dependencyManagement>` decouples version curation from the inheritance slot. The trade-off is we pin Maven plugin versions ourselves, but that's a one-time cost in the root POM."

---

## 6. Parent Resolution Chain — 點解 `<relativePath>` 重要

當 child pom 有 `<parent>`，Maven 順序試：

```
Step 1: 跟 <relativePath> 喺 filesystem 揾
        ↓ 揾唔到（path 錯 / 漏寫）
Step 2: 喺 local repo (~/.m2/repository/) 揾已 install 嘅 parent jar
        ↓ 揾唔到（parent 未 mvn install 過）
Step 3: 喺 remote repository (Maven Central / Nexus / GitHub Packages) 揾
        ↓ 揾唔到（私人 project，從未 publish）
❌ BUILD FAILS
```

### 第一次 build 嘅 chicken-and-egg

Fresh project 第一次 `mvn clean install` 喺 root：
- Maven 嘗試 build `common-events`（reactor 第一個）
- Child `<parent>` resolution → step 1 用錯 / 漏 relativePath → 失敗
- → step 2: local repo 揾唔到 parent（**因為 build 仲未開始**！parent 未 install）
- → step 3: remote 揾唔到（私人 project 從未 publish）
- 💥 build fails before parent 都未有機會 install

### 我哋嘅 folder 結構需要 `<relativePath>../../pom.xml>`

```
OnlineShopping-Microservices/
├── pom.xml                              ← root
├── shared/common-events/pom.xml         ← 兩層深 → ../../pom.xml
├── shared/common-dto/pom.xml            ← 兩層深 → ../../pom.xml
└── services/user-service/pom.xml        ← 兩層深 → ../../pom.xml
```

唔寫 = Maven default `../pom.xml` = 喺 `shared/pom.xml` 揾 = 揾唔到 = 經 step 2/3 = fail。

> **Mental model**: `<relativePath>` 係**正路**，唔係 optimization。Local / remote repo 係 **cross-project sharing**，唔係 intra-project reference。

---

## 7. Shared Library Trap — Microservices 反 Pattern

### Microservices 自治原則

每個 service 應該可以**獨立 deploy + version + scale**。如果 8 個 service 共用一個會變嘅嘢 → 改一個地方 → 重 deploy 8 個 service → 等於 distributed monolith（最壞情況：拆咗 service 嘅複雜度，但攞唔到拆嘅好處）。

### 邊啲 jar OK 共享？邊啲係毒藥？

| Type | 例子 | OK 共享？ | 點解 |
|------|------|-----------|------|
| Cross-service event POJO | `OrderCreatedEvent`, `PaymentChargedEvent` | ✅ | Schema 由 message contract 定，本身就係 cross-service truth；version 變動有 schema evolution 套路（add field optional / new event class） |
| Read-only DTO envelope | `ApiResponse<T>`, `PageResponse<T>` | ✅ | 純結構，無 logic，唔關 business model |
| Constants / Enums (cross-cutting) | HTTP header name, error code prefix | ✅（小心）| 真係 cross-service 共識先放，否則 service-local |
| **Domain entity** | `User`, `Order`, `Product` (JPA `@Entity`) | ❌ **死亡陷阱** | 改一個 field → 8 service redeploy；Order context 嘅 Product 同 Cart context 嘅 Product 唔同（DDD bounded context — 見 L1 §5）|
| Repository / DAO | `UserRepository`, `ProductRepository` | ❌ | DB-per-service 原則：repository 屬於 owning service，唔該分享 |
| Business logic / Service class | `OrderService`, `PaymentService` | ❌ | Service ownership 違反，logic drift |
| Persistence config | `@Configuration` for DataSource | ❌ | 每 service 自己 DB，自己配 |

### 經驗法則（一句記住）

> **Share schema, not implementation. Share structure, not behavior.**

我哋 `common-events` 同 `common-dto` 兩個 module 都係 schema/structure 類別。**永遠唔好喺 shared module 入面放 entity / repository / business logic / persistence config**。

### Anti-pattern detection drill

下次見到一個 shared module 入面有：
- `import jakarta.persistence.*;` → 🚨 Entity 入侵
- `@Service` / `@Repository` 標註 → 🚨 business logic 入侵
- `@Autowired` / `@Inject` → 🚨 DI wiring 入侵

→ 即時 refactor，將呢啲嘢搬返 owning service。

---

## 8. Distributed ID Strategy — by Use Case

### 3 種主流策略

| Strategy | Bits | Sortable by time? | Coordination needed? | Storage |
|----------|------|---------------------|------------------------|----------|
| **UUID v4** (random) | 128 | ❌ | ❌ stateless | 16 bytes |
| **UUID v7** (time-ordered) | 128 | ✅ first 48-bit unix ms + random | ❌ stateless | 16 bytes |
| **Snowflake** | 64 | ✅ 41-bit timestamp + 10-bit machine + 12-bit seq | ✅ 要分配 machine ID | 8 bytes |

### 應用場景

| Use case | 揀邊個 | Why |
|----------|--------|-----|
| **Event ID**（Kafka message key, event log） | UUID v4 | Event 自己有 `occurredAt` 時間戳；event ID 只需 collision-free + 完全 stateless |
| **Entity primary key**（User.id, Order.id, Payment.id）| **Snowflake** 或 **UUID v7** | DB B-tree index — random UUID v4 導致 page split + write amplification（高 QPS 災難）；time-ordered ID 永遠寫 B-tree 尾部 hot page，fast |
| **Idempotency key**（client retry token）| UUID v4（client-generated）| Server stateless dedupe |

我哋 `DomainEvent.eventId()` 用 **UUID v4** 啱（透過 `UUID.randomUUID()` 一行 generate）。Entity primary key 嘅 Snowflake 討論留 **L3 — Database Decomposition**。

### Interview 答法（resume +1）

> **Q: How do you generate IDs in a distributed system?**
>
> "Depends on use case. Event IDs use UUID v4 — events have their own timestamp, so ID sortability isn't needed and statelessness matters. **Entity primary keys** I'd use **Snowflake or UUID v7** to keep the B-tree index sequential and avoid write amplification. **Idempotency keys** are client-generated UUIDs."

---

## 9. Maven Wrapper — Reproducible Builds

跑一次：
```bash
mvn wrapper:wrapper -Dmaven=3.9.15
```

Generate：
- `mvnw` (Unix shell)
- `mvnw.cmd` (Windows batch)
- `.mvn/wrapper/maven-wrapper.properties` ← 釘 Maven version 入 source code

之後**唔再用** `mvn`，全部用 `./mvnw`：
```bash
./mvnw clean install
./mvnw spring-boot:run -pl services/user-service
```

### 點解必要

| 場景 | 冇 wrapper | 有 wrapper |
|------|-----------|------------|
| 新隊友 onboard | "你裝 Maven 邊個 version？" 浪費半日 | clone repo → `./mvnw` 自動 |
| CI/CD（GitHub Actions） | 要 setup-maven action 同步 version | `./mvnw` 直接 |
| 本機 vs 同事 vs CI 三邊跑 build | 三套 Maven version → 隱性 bug | 同一 binary，無 drift |

> **Resume keyword**: "**Reproducible builds** via Maven Wrapper" — FAANG 面試講得出 +1。

---

## 10. Verification

### Build chain

```bash
./mvnw clean install
```

預期見到 reactor build 順序（topological sort）：
```
[INFO] Reactor Build Order:
[INFO] OnlineShopping Microservices Parent ...... [pom]
[INFO] Common Events ............................. [jar]
[INFO] Common DTO ................................ [jar]
[INFO] User Service .............................. [jar]
```

`common-events` 同 `common-dto` 兩個冇 inter-dependency → 任意先後；`user-service` 必喺兩個 shared 之後 build（因為 dependency declare）。

### Endpoint 試運行

```bash
./mvnw spring-boot:run -pl services/user-service
```

```bash
curl http://localhost:8080/health
# {"success":true,"data":"user-service is up","errorMessage":null}
```

證明：
- BOM curation work（`spring-boot-starter-web` 冇寫 version 都 resolve）
- Cross-module reference work（`HealthController` import 到 `com.onlineshopping.dto.ApiResponse`）
- Spring Boot fat jar 透過 `spring-boot-maven-plugin` 打成功

---

## 11. Interview Prep / Resume Points

### 5 條典型問題答法

**Q1: Explain Maven multi-module project structure.**
- Aggregation (`<modules>`) vs Inheritance (`<parent>`) 兩件唔同嘅嘢，orthogonal
- Aggregation = build order；Inheritance = config 繼承
- 99% 同時用，但要識分

**Q2: What's the difference between `<dependencyManagement>` and `<dependencies>`?**
- `<dependencies>` cascade 落 children；`<dependencyManagement>` 只 declare version/scope，children 要顯式 opt-in（vending machine）
- Multi-module + microservices 用 `<dependencyManagement>` 避免 force include

**Q3: Why use BOM import instead of inheriting `spring-boot-starter-parent`?**
- `<parent>` slot 要留俾 own aggregator POM
- BOM import 透過 `<scope>import</scope>` 攞 version curation 而唔佔 inheritance slot
- Trade-off: 自己 pin maven plugins（fixed cost）

**Q4: How do you generate IDs in distributed systems?**
- Event ID: UUID v4（stateless, no time-ordering needed）
- Entity PK: Snowflake / UUID v7（time-ordered, B-tree friendly）
- Idempotency key: client-generated UUID

**Q5: What is the "shared library trap" in microservices?**
- Sharing entity / repository / business logic across services = distributed monolith（schema change → redeploy 8 services）
- 只 share schema 性質嘢（events, DTOs, constants）；唔好 share implementation

### Resume Bullet (interim)

> "Designed multi-module Maven project with **BOM import pattern** instead of `spring-boot-starter-parent` to enable independent service inheritance; pinned plugin versions and added Maven Wrapper for **reproducible builds** across local, CI, and production."

---

## 12. Homework / Reflection

> 自己諗完先 expand 答案。

### 1. 如果有人 PR 一個叫 `common-domain` 嘅新 module 入嚟，入面有 `User.java` `@Entity`，你點 review？拒絕嘅理由有哪 3 條？

<details>
<summary>📝 Solution</summary>

拒絕呢個 PR 唔係因為 code style，係因為佢同時 violate **三個獨立原則**，每條都係 microservices 嘅核心 commitment：

| # | 角度 | 災難 |
|---|------|------|
| 1 | **Database coupling** | `@Entity` 暗示 ORM 對應一張 table。所有 import 呢個 module 嘅 service 隱含 share 同一張 user table → DB-per-service 原則直接破。 |
| 2 | **Deployment coupling** | User schema 加一個 field → common-domain 出新 version → 所有 dependent service 全部要 rebuild + redeploy → 失去獨立 deployment → 變相 distributed monolith。 |
| 3 | **Domain boundary violation (DDD)** | User 嘅 business rule（password policy / email validation / role transition）只屬 User service。共享 entity = 共享 mutation logic = 多個 service 都可以 mutate 同一個 user state → bounded context 邊界破。 |

**Bonus 第 4 條**：testing coupling — common-domain 改一個 method signature → 所有 downstream service test 跟住 break。

**邊啲嘢可以放 shared module？**
- ✅ `common-events`：immutable event payload (DTO with no behaviour, no `@Entity`)
- ✅ `common-dto`：API request/response DTO (cross-service contract)
- ❌ Entity / Repository / Service / business logic — **絕對唔可以**

**面試 punch line**：「拒絕呢個 PR 唔係 code style 問題，係佢同時 violate database coupling、deployment coupling 同 domain boundary 三個獨立原則 — 每條都係 microservices 嘅 first principle。共享 DTO 可以，共享 entity 等於用微服務嘅 packaging 寫返一個 monolith。」

</details>

---

### 2. 點解我哋 root pom 嘅 `<dependencyManagement>` 入面要 declare `common-events` 同 `common-dto`？如果唔 declare，service module 自己加 dependency 會點？

<details>
<summary>📝 Solution</summary>

**Declare 喺 parent 嘅作用：single source of truth for version。**

Parent declare 之後：
```xml
<!-- service pom：只寫 groupId + artifactId，唔寫 version -->
<dependency>
    <groupId>com.onlineshopping</groupId>
    <artifactId>common-events</artifactId>
</dependency>
```

唔 declare 嘅後果：
```xml
<!-- 每個 service 都要自己寫 version -->
<dependency>
    <groupId>com.onlineshopping</groupId>
    <artifactId>common-events</artifactId>
    <version>1.0.0-SNAPSHOT</version>  <!-- 7 個 service 都要寫 -->
</dependency>
```

**會出咩問題：**

1. **Version drift** — `user-service` 寫 `1.0.0`，`order-service` 寫 `1.1.0`，runtime classpath 唔知用邊個（depends on Maven 解析次序），出 weird `NoSuchMethodError`。
2. **Bump 成本爆炸** — common-events 出 `1.2.0` → 7 個 service pom 都要逐個改。Parent declare 嘅話改一行 property 就掂。
3. **Audit / SBOM 唔 consistent** — security scan / dependency report 出多個 version，難 trace。

**面試 punch line**：「`<dependencyManagement>` 等於 vending machine：parent 印 menu（version），child opt-in（用 `<dependencies>` 唔寫 version）。Single source of truth 唔止係 best practice，係 multi-module project 嘅 minimum bar。」

</details>

---

### 3. 你 monolith 個 single-module pom 有冇 `<pluginManagement>`？如果冇，係靠 `spring-boot-starter-parent` 默認 pin？

<details>
<summary>📝 Solution</summary>

呢條問題有兩 part，要兩部分都答：

**(a) Monolith 有冇 `<pluginManagement>`？**

**冇**。Monolith pom inherit `spring-boot-starter-parent`，由 starter-parent 自動 pin 住所有常用 plugin：
- `maven-compiler-plugin` (對應 `<java.version>`)
- `maven-surefire-plugin` / `maven-failsafe-plugin` (test runner)
- `spring-boot-maven-plugin` (`bootRun`, `bootJar`)
- `maven-jar-plugin`、`maven-resources-plugin` 等

**(b) 點解 microservices 呢個 project 反而要自己寫 `<pluginManagement>`？**

因為我哋揀咗 **BOM import (Way 2)** 而唔係 `spring-boot-starter-parent` (Way 1)：

| 揀法 | Plugin pinning |
|------|----------------|
| **Way 1**: `<parent>spring-boot-starter-parent</parent>` | 自動 pin (free) |
| **Way 2**: `<dependencyManagement>` BOM import | 自己手動 pin（fixed cost） |

點解仲要揀 Way 2？因為 multi-module 入面 `<parent>` 個 slot 我哋要留俾自己嘅 aggregator POM (`onlineshopping-microservices-parent`)，唔可以同時又 inherit `spring-boot-starter-parent`。

**結論**：BOM import 唔 free — 換到 version curation 嘅同時要付 plugin pinning 嘅 fixed cost。我哋 root pom 嘅 `<pluginManagement>` 就係呢個 cost。

**面試 punch line**：「Monolith 用 starter-parent，plugin 自動搞掂。Multi-module microservices 通常用 BOM import 因為 `<parent>` slot 要俾自己 aggregator，所以要自己寫 `<pluginManagement>` 補返呢個 fixed cost — trade-off 但值得。」

</details>

---

### 4. 如果而家換項目 `<spring-boot.version>3.5.14 → 3.6.0</spring-boot.version>`，所有 service 要唔要改自己 pom？點解？

<details>
<summary>📝 Solution</summary>

**唔需要改 service pom。** 改 parent 一行 property 就完。

點解 work？睇 chain：

```
parent pom.xml
├─ <properties>
│    <spring-boot.version>3.5.14</spring-boot.version>   ← 改呢度
│
└─ <dependencyManagement>
     <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-dependencies</artifactId>
       <version>${spring-boot.version}</version>          ← 跟住 resolve
       <type>pom</type>
       <scope>import</scope>
     </dependency>
   </dependencyManagement>

service pom.xml (e.g. user-service)
└─ <dependencies>
     <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-web</artifactId>   ← 唔寫 version
     </dependency>                                         ← 跟 parent BOM 攞
```

Bump `3.5.14 → 3.6.0`：
1. Parent property 改一個字
2. BOM import resolve 出 `spring-boot-dependencies:3.6.0`
3. Service 入面所有 `spring-boot-starter-*` / Jackson / Hibernate / Tomcat 等 transitive dep 自動跟住升
4. **Service pom 一個字都唔使改**

**注意 caveat**：唔代表唔會炒
- 大版本升（e.g. `3.x → 4.x`）可能有 breaking API change → service code 要改（但 pom 仲係唔使改）
- Patch / minor 版升通常 transparent

**面試 punch line**：「BOM import + parent property 嘅最大 payoff 就喺呢度 — 一個字 bump 全 fleet 跟住升。如果每個 service 都自己寫 spring-boot version，呢個動作要改 7 個 pom + 7 個 review + 7 個 risk window，係 distributed monolith 嘅 maintenance cost 嘅典型表現。」

</details>

---

## 13. 下一步 — Lesson 03 預告

**L3 — Database Decomposition: From Single Schema to DB-per-Service** ⭐

- 由 monolith ER 圖出發，畫每個 table 應該屬邊個 service
- Cross-service join 三種解法：API composition / data denormalization / CQRS materialized view
- Cart 由 SQL → DynamoDB 全新 design（single-table design 預告）
- 過渡 migration strategy：dual-write → shadow-read → cutover
- Snowflake ID 喺 entity primary key 嘅深入討論
- 開 branch `lesson-03-db-decomposition`

---

## References

- Maven 官方文檔: https://maven.apache.org/guides/introduction/introduction-to-the-pom.html
- Spring Boot Multi-Module Tutorial: https://spring.io/guides/gs/multi-module/
- Sam Newman, *Building Microservices* (2nd ed., 2021), Ch.5 (Implementing Microservice Communication)
- Maven Wrapper: https://maven.apache.org/wrapper/
- UUIDv7 spec: RFC 9562 (May 2024)
- Twitter Snowflake: https://github.com/twitter-archive/snowflake (archived but design canonical)
