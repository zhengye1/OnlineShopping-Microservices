# Lesson 09 — Choreography Saga + Inventory Reservation (Strategy 2)

**Branch:** `lesson-09-saga`
**Builds on:** L6 (Kafka), L7 (cart-service + JWT), L8 (Resilience4j)
**Services touched:** new `order-service` (5th microservice), inventory-service, cart-service
**Topic taxonomy:** order-events, inventory-saga-events, payment-requests, payment-events

---

## Learning Objectives

By the end of this lesson you should be able to:

1. Explain **why 2PC doesn't work in microservices** and how Saga's local transactions + compensation pattern replaces it.
2. Compare **choreography vs orchestration** sagas with concrete trade-offs (debugging, coupling, single point of failure).
3. Design **fine-grained state machine** for an order aggregate so each saga step is observable from a single SQL query.
4. Implement **atomic conditional UPDATE** (`WHERE stock - reserved >= :qty`) to eliminate check-then-act oversell race.
5. Build a **TTL reaper** with per-record transaction isolation so partial sweep failures don't poison the loop.
6. Use **natural state-machine guard** instead of a processed_events table for saga event idempotency.
7. Diagnose three concrete saga production traps: ConsumerRecord parameter binding, trustedPackages mismatch, and the **payment race** that requires PaymentRequestedEvent to serialize the saga.

---

## 1. The Distributed Transaction Problem

L7 / L8 你已經 build 咗 4 個 service 各自有自己 DB。L9 嘅 use case 簡單：用戶 checkout，要扣 inventory + charge payment + create order — **3 個唔同 service 操作 3 個唔同 DB，但要 all-or-nothing**。

### 為何 ACID transaction 跨唔過 service boundary

```
DB transaction (single DB):
  BEGIN
  UPDATE orders SET status='CREATED'
  UPDATE inventory SET reserved_stock += qty
  UPDATE payment SET status='CHARGED', amount=...
  COMMIT  ← 任何 step 死，全部 rollback ✅
```

睇起嚟係 trivial — 但係喺微服務世界，呢個 query span 跨 service + 跨 DB。要做 atomic 嘅話需要 **2-Phase Commit (2PC)**：

```
Transaction Coordinator
       ↓
  PHASE 1: Send "prepare" to all participants
       ↓
  Wait all participants reply "ready" (write to durable log)
       ↓
  PHASE 2: Send "commit" or "abort"
       ↓
  Wait all confirmations
```

### 2PC 嘅 5 個 production-killing problems

| Problem | Concrete fail mode |
|---|---|
| **Blocking** | Coordinator crash 喺 phase 1 同 phase 2 之間 → participants 永遠 stuck 喺 "in-doubt" state |
| **Latency** | 每個 transaction 要 4× round-trip (prepare/ack + commit/ack)，跨 service call 變超慢 |
| **Team boundary** | 4 個 service 4 個 team — 唔可能共用 transaction manager (who owns it?) |
| **CAP penalty** | 2PC 犧牲 availability for consistency — partition 期間全 stack 凍 |
| **Lock 太耐** | Rows locked 整個 transaction lifetime — throughput 跌 10x+ |

### Saga 嘅 core idea

> 用 **series of local transactions + compensating actions** 取代 single distributed transaction。
> Each step commit 入 local DB；如果後續 step fail，run **compensating action** 去 logically 反轉之前 step。

L9 嘅 saga：

```
1. Order created (PENDING_INVENTORY)        ← local in order DB
2. Inventory reserve stock atomically       ← local in inventory DB
3. Payment charge                            ← local in payment DB (mock)
4. Order CONFIRMED                           ← local in order DB

If step 3 fails:
  compensate step 2: release inventory reservation
  mark order CANCELLED
```

---

## 2. Choreography vs Orchestration

| Flavor | 比喻 |
|---|---|
| **Choreography (編舞)** | 一班 dancer，冇 director — 每個 dancer 睇住 partner 嘅 move 跟住跳 |
| **Orchestration (指揮)** | 一個 conductor — 每個樂手聽指揮做嘢 |

### Choreography 對應到 microservices

- 每個 service publish event 完成自己 step
- 其他 service 自己 subscribe + 自主決定點 react
- 冇 central coordinator
- L9 用呢個

### Orchestration 對應到 microservices

- 一個 central "Saga Coordinator" service
- 按 sequence call 每個 service 嘅 API
- 收到 response 先決定下一步
- 失敗就 invoke compensating endpoint

### Trade-off matrix

| Dimension | Choreography (L9 揀) | Orchestration |
|---|---|---|
| **Coupling** | 每 service 獨立 react to events | Central coordinator knows whole flow |
| **Single point of failure** | 冇 (Kafka 已經 distributed) | Coordinator crash = saga frozen |
| **Visibility / debugging** | ⚠️ 邏輯散落 — 要靠 correlation ID + distributed tracing | ⭐ Central state machine view |
| **加 new step** | 加 new consumer，唔影響舊 service | Modify coordinator code + redeploy |
| **適合 scale** | Linear / simple workflow | Complex multi-branch workflow |

### 點解 L9 揀 Choreography

1. **Infra 已 ready** — L6 已落 Kafka，每 service 已 publish/subscribe pattern
2. **Linear flow** — CREATED → RESERVED → CHARGED → CONFIRMED，冇 branch
3. **避免 god service** — 加 coordinator service 即係加 5th maintenance burden
4. **Senior interview signal** — 答案應該係「choreography for linear sagas, orchestration when workflow branches」— 而唔係教條式偏好

### 但 choreography 嘅 tradeoff 你要記住

```
production incident: order #12345 起咗，但 user 收唔到 confirmation
→ Saga 而家 stuck 喺邊一步？

Orchestration: SELECT * FROM saga_execution WHERE order_id = 12345 → 一條 row 答你
Choreography:  Grep 4 個 service 嘅 log，根據 correlation ID 拼回 timeline
```

呢個就係**你 L7 寫嘅 CorrelationIdFilter 嘅 ROI 喺 L9 真正爆發**。

---

## 3. Phase 1 — Apply CB + Retry to InventoryClient (Warm-up)

L8 hw Q1 落地。Mirror L8 ProductClient pattern 但係 **fallback strategy 完全唔同**。

### Why the cart_items cache trick doesn't carry over

```
ProductClient fallback:
  cart_items.priceAtAddition + currency → 重建 ProductSummary ✅

InventoryClient fallback:
  cart_items 冇 stock_quantity snapshot — 我哋只 snapshot price，唔 snapshot stock
  即使 snapshot 咗 stock，stock 變化快 — 30 秒前嘅 stock 已經唔可靠
  Risk: 用 stale stock allow add → 之後 checkout 撞 oversell → user 失望
```

### 決策 matrix — inventory degraded mode per scenario

| Scenario | Fallback strategy | Why |
|---|---|---|
| **Add-to-cart** (我哋做嘅) | Fail-fast 503 | 拒絕 < oversell — correctness > availability |
| **Browse product page (read-only stock)** | Cached stock OK，標 "approximate" | UX > correctness — browsing 唔 commit |
| **Checkout (L9 saga)** | Hard 503 + retry queue | Financial — 絕對唔可以 oversell |

### Code (key pieces)

```java
@Service
public class ResilientInventoryClient {
    @CircuitBreaker(name = "inventoryClient", fallbackMethod = "getStockFallback")
    @Retry(name = "inventoryClient")
    public InventoryStock getStock(Long productId) {
        return inventoryClient.getStock(productId);
    }

    public InventoryStock getStockFallback(Long productId, Throwable cause) {
        if (cause instanceof FeignException.NotFound) {
            throw new ResponseStatusException(NOT_FOUND, "Inventory record missing: " + productId);
        }
        // No safe cache — refuse rather than risk oversell.
        throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                "Inventory service unavailable; cannot verify stock");
    }
}
```

### 🎯 Failure isolation per downstream

```yaml
resilience4j:
  circuitbreaker:
    instances:
      productClient:      # L8 CB — independent state machine
        ...
      inventoryClient:    # L9 CB — independent state machine
        ...
```

> Inventory 死 → 唔應該 trip product 嘅 CB。
> Product 死 → 唔應該 trip inventory 嘅 CB。

每個 downstream 應該有獨立 CB instance。

---

## 4. Phase 3 — order-service Scaffold (5th Microservice)

5th 微服務 — port 8085, MySQL 3311.

### Fine-grained vs coarse-grained state machine

| Design | States | Trade-off |
|---|---|---|
| **A (Coarse)** | PENDING → CONFIRMED / CANCELLED | Simple but loses observability of which step failed |
| **B (Fine) ⭐** | PENDING_INVENTORY → PENDING_PAYMENT → CONFIRMED → CANCELLED | Each saga step gets its own state; SRE-friendly |

L9 揀 B — production incident query `SELECT status FROM orders WHERE status NOT IN ('CONFIRMED','CANCELLED') AND created_at > NOW() - INTERVAL 1 HOUR` 一眼睇晒 stuck 喺邊。

### Schema highlights

```sql
CREATE TABLE orders (
    id                  BIGINT PRIMARY KEY,            -- Snowflake-generated
    user_id             BIGINT NOT NULL,
    status              VARCHAR(32) NOT NULL,          -- enum as string (evolution-friendly)
    total_amount_cents  BIGINT NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    cancel_reason       VARCHAR(64) NULL,              -- forensics column
    idempotency_key     VARCHAR(128) NULL UNIQUE,      -- DB enforces dedup
    version             BIGINT NOT NULL DEFAULT 0,     -- optimistic lock
    ...
);
```

### Idempotency-Key + DB UNIQUE

```java
public Order create(Long userId, CreateOrderRequest req, String idempotencyKey) {
    if (idempotencyKey != null) {
        var existing = orderRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return existing.get();  // short-circuit
    }
    ...
}
```

DB 嘅 UNIQUE constraint 係 single source of truth — 即使 race 過 application check，DB level 都 reject second insert。

---

## 5. Phase 4 — Inventory Reservation: Atomic UPDATE + State Machine

L7 hw Q4 落地。Soft reservation pattern — Ticketmaster style，holds stock 暫時 + TTL release。

### 3-state machine

```
ACTIVE ──saga payment success──► COMMITTED  (terminal)
ACTIVE ──saga compensation────► RELEASED   (terminal, release_reason='PAYMENT_FAILED')
ACTIVE ──TTL reaper───────────► RELEASED   (terminal, release_reason='TTL_EXPIRED')
ACTIVE ──user cancel──────────► RELEASED   (terminal, release_reason='USER_CANCELLED')
```

`release_reason` 係 forensics column — incident review 嗰時知道 reservation 點解 ended。

### Atomic conditional UPDATE — race elimination

```sql
UPDATE inventories
   SET reserved_stock = reserved_stock + :qty,
       version = version + 1
 WHERE product_id = :productId
   AND stock_quantity - reserved_stock >= :qty
```

**WHY this works:**

```
Read-check-update (RACE):
  1. SELECT stock_quantity, reserved_stock     ← race window 開始
  2. (caller checks if stock >= qty)
  3. UPDATE SET reserved_stock = ?              ← race window 結束
  
  Two concurrent reservers can both pass step 2 → oversell

Atomic conditional UPDATE (NO race):
  Single statement
  MySQL row lock held during the check + update
  WHERE clause arithmetic evaluated INSIDE the lock
  → Returns 0 affected rows if stock insufficient
```

### State machine guard via affected-rows check

```java
@Transactional
public void commitForOrder(Long orderId) {
    List<InventoryReservation> active =
        reservationRepo.findByOrderIdAndStatus(orderId, ACTIVE);
    for (InventoryReservation r : active) {
        int affected = inventoryRepo.commitReserved(r.getProductId(), r.getQuantity());
        if (affected == 0) {
            throw new IllegalStateException("Reservation commit accounting broke");
        }
        r.setStatus(COMMITTED);
        reservationRepo.save(r);                     // @Version catches concurrent transitions
    }
}
```

呢個結構保證：兩個 race 嘅 handler (payment-success vs TTL reaper) 只有一個成功。

### TTL reaper — per-record transaction

```java
@Scheduled(fixedDelayString = "${app.reservation.reaper-interval-ms:60000}")
public void releaseExpired() {
    List<InventoryReservation> expired = reservationRepo.findExpiredActive(now());
    for (InventoryReservation r : expired) {
        try {
            releaseSingleExpired(r);                  // own transaction
        } catch (Exception e) {
            log.error("TTL reaper failed for id={}, continuing sweep", r.getId(), e);
        }
    }
}
```

每個 reservation 一個 transaction — 一個失敗唔會 roll back 已經 release 嘅 progress。Per-record transaction granularity is the production-correct pattern.

---

## 6. Phase 5+6 — Saga Choreography Flow

### Topic taxonomy (4 個 topic, ownership boundaries)

| Topic | Owned by | Events |
|---|---|---|
| `order-events` | order-service | OrderCreatedEvent, CompensateReservationEvent |
| `inventory-saga-events` | inventory-service | StockReservedEvent, StockReservationFailedEvent |
| `payment-requests` | order-service | PaymentRequestedEvent |
| `payment-events` | payment-mock | PaymentChargedEvent, PaymentFailedEvent |

**Ownership rule**: a service only publishes to topics it owns. 嚴格 bounded-context discipline.

### Happy path flow

```
POST /orders
  ↓
order-service:
  ├─ Snowflake ID + persist Order PENDING_INVENTORY
  ├─ Idempotency-Key dedup
  └─ Publish OrderCreatedEvent → order-events
                                    │
                                    ▼
                            inventory-service.OrderEventListener:
                            ├─ ReservationService.reserve atomically (per item)
                            ├─ Success: Publish StockReservedEvent → inventory-saga-events
                            └─ 409: Publish StockReservationFailedEvent
                                    │
                                    ▼
                            order-service.SagaEventListener:
                            ├─ handleStockReserved:
                            │    1. Order PENDING_INVENTORY → PENDING_PAYMENT
                            │    2. Publish PaymentRequestedEvent → payment-requests
                                    │
                                    ▼
                            PaymentMockService:
                            ├─ Listen PaymentRequestedEvent
                            ├─ Decision: amount > threshold → fail, else → success
                            └─ Publish PaymentChargedEvent / PaymentFailedEvent → payment-events
                                    │
                                    ▼
                            order-service.SagaEventListener:
                            ├─ handlePaymentCharged → Order CONFIRMED ✅
                            └─ handlePaymentFailed →
                                 Order CANCELLED + publish CompensateReservationEvent
                                                                    │
                                                                    ▼
                                                       inventory-service.OrderEventListener:
                                                       ├─ ReservationService.releaseForOrder
                                                       └─ reservation ACTIVE → RELEASED
```

### Natural state-machine guard for saga event idempotency

L6 用 `processed_events` 表做 dedup。L9 唔需要 — order status 本身就係 natural idempotency key：

```java
@Transactional
public void handleStockReserved(Long orderId) {
    Order order = orderRepo.findById(orderId).orElse(null);
    if (order.getStatus() != OrderStatus.PENDING_INVENTORY) {
        // Duplicate / out-of-order event — already past this state. Skip.
        return;
    }
    order.setStatus(OrderStatus.PENDING_PAYMENT);
    ...
}
```

**Why this is cleaner than processed_events for sagas:**
- State transitions ARE the dedup signal
- Two `StockReservedEvent` for same order → second one fails the status check naturally
- No extra table, no extra writes
- Aligns with state machine semantics

---

## 7. Sequence Diagram

```
                  POST /orders                          
User ───────────► order-service                          
                       │ persist Order(PENDING_INVENTORY)
                       │ publish OrderCreatedEvent       
                       │                                 
                       ▼ (order-events)                 
              inventory-service                          
                       │ tryReserve atomic UPDATE        
                       │ INSERT reservation ACTIVE       
                       │ publish StockReservedEvent      
                       │                                 
                       ▼ (inventory-saga-events)         
              order-service.SagaEventListener            
                       │ Order → PENDING_PAYMENT         
                       │ publish PaymentRequestedEvent   
                       │                                 
                       ▼ (payment-requests)              
              PaymentMockService                          
                       │ decide success/fail              
                       │ publish PaymentChargedEvent       
                       │                                 
                       ▼ (payment-events)                
              order-service.SagaEventListener            
                       │ Order → CONFIRMED ✅              

      (Failure branch:)                                  
              order-service                              
                       │ Order → CANCELLED              
                       │ publish CompensateReservationEvent
                       │                                 
                       ▼ (order-events)                  
              inventory-service                          
                       │ releaseReserved atomic UPDATE   
                       │ reservation ACTIVE → RELEASED   
                       │ release_reason set              
```

---

## 8. Testing Strategy

### Tests added

| Layer | File | Cases |
|---|---|---|
| **Unit** (Phase 1 carryover) | CartServiceTest | 5 |
| **Integration** (CartService L7/L8) | CartControllerIntegrationTest | 8 |
| **Observability** (L8) | ResilienceObservabilityTest | 2 |
| **Integration** (Inventory L6) | InventoryServiceTest | 6 |
| **Integration** (L9 saga) | OrderSagaIntegrationTest | 5 |

### Saga test infra pattern

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
    @ServiceConnection
    static final MySQLContainer<?> MYSQL;       // singleton (L8 war story)

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA;  // singleton (L8 war story)

    static {
        MYSQL.start();
        Runtime.getRuntime().addShutdownHook(new Thread(MYSQL::stop));
        KAFKA.start();
        Runtime.getRuntime().addShutdownHook(new Thread(KAFKA::stop));
    }
    ...
}
```

### Saga test simulation pattern

Test 模擬 inventory-service 嘅 events 用 test-side KafkaTemplate publishing 直接到 topic：

```java
@Test
void saga_happyPath_stockReserved_thenPaymentCharged_orderConfirmed() throws Exception {
    Long orderId = postOrderAndExtractId(5000L, 2);    // total=10000 cents

    // Simulate inventory-service publishing StockReservedEvent.
    publishStockReserved(orderId, 10000L, "CAD");

    // Awaitility bridges sync POST + async Kafka propagation.
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> {
            Order o = orderRepo.findById(orderId).orElseThrow();
            assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        });
}
```

PaymentMockService 喺真實 component runs，唔需要 mock — 佢 consume 真 Kafka event 然後 publish 真 payment outcome。咁 saga 完整路徑都喺 test 嗰陣行過。

---

## 9. War Stories

### War story #1 — Spring Kafka `Object event` parameter delivers raw ConsumerRecord, not deserialized payload

**Symptom**: SagaEventListener log shows `Unknown inventory-saga-event type: class org.apache.kafka.clients.consumer.ConsumerRecord`. Events arrived but `instanceof StockReservedEvent` always false.

**Diagnosis**: When @KafkaListener method declares `Object event`, Spring Kafka binds the parameter to the entire `ConsumerRecord` wrapper, not the deserialized value. `@Payload Object event` did NOT fix it.

**Fix**: declare the parameter as `ConsumerRecord<String, Object> record` and extract `record.value()` explicitly:

```java
@KafkaListener(...)
public void onInventorySagaEvent(ConsumerRecord<String, Object> record, Acknowledgment ack) {
    Object event = record.value();
    if (event instanceof StockReservedEvent e) { ... }
}
```

**Senior lesson**: Spring Kafka's method parameter binding is convention-heavy. When you want the payload typed, either declare the exact event type as the parameter (and use `@KafkaHandler` for multi-type dispatch) or use the explicit ConsumerRecord pattern. Object alone is ambiguous.

### War story #2 — JsonDeserializer trustedPackages must include the event package

**Symptom**: Events were published but the consumer either deserialized them as LinkedHashMap or rejected them with a security exception when typed packages weren't trusted.

**Diagnosis**: `spring.json.trusted.packages` defaults are strict for security. Our service-local event packages (e.g. `com.onlineshopping.order.event`) need to be explicitly listed.

**Fix**:
```yaml
spring.kafka.consumer.properties.spring.json.trusted.packages:
  "com.onlineshopping.order.event,com.onlineshopping.common.events"
```

**Senior lesson**: JsonDeserializer's trusted-package security is opt-in by design — listing every event package is annoying but safer than blanket trusting `*`. The fix per service is local config rather than central, matching the bounded-context discipline.

### War story #3 — Saga payment race: PaymentMock listening to StockReservedEvent races with order's own state transition

**Symptom**: Happy path test always timed out at PENDING_INVENTORY. Log showed `Received PaymentChargedEvent` BEFORE `Order PENDING_INVENTORY → PENDING_PAYMENT`. PaymentChargedEvent processed against PENDING_INVENTORY order → handlePaymentCharged status guard rejected it → order stuck.

**Diagnosis**: PaymentMockService was listening to StockReservedEvent on inventory-saga-events — the same topic order-service's SagaEventListener consumes. Both consumers process in parallel. PaymentMockService can publish PaymentChargedEvent BEFORE order-service has transitioned the state, leading to a lost event due to the state guard.

**Fix**: Introduce a new event `PaymentRequestedEvent` published by order-service AFTER the PENDING_INVENTORY → PENDING_PAYMENT transition. PaymentMockService listens to this on a new `payment-requests` topic.

```java
@Transactional
public void handleStockReserved(Long orderId) {
    // ... state transition ...
    order.setStatus(PENDING_PAYMENT);
    orderRepo.save(order);

    // Publish PaymentRequestedEvent AFTER the transition is committed.
    kafkaTemplate.send(paymentRequestsTopic, ..., new PaymentRequestedEvent(...));
}
```

**Senior lesson**: Choreography sagas are *not* "every service listens to every event." Carefully think about which events trigger which downstream actions, and use explicit `*Requested*` events to serialize state transitions that must happen before downstream processing. Without this, you get races between sibling consumers of the same event.

### War story #4 — V1 Flyway DDL using CHAR(3) conflicts with JPA @Column(length=3) validating as VARCHAR

**Symptom**: Spring Boot startup fails with `Schema-validation: wrong column type encountered in column [currency] in table [order_items]; found [char (Types#CHAR)], but expecting [varchar(3) (Types#VARCHAR)]`.

**Fix**: Change V1 DDL `CHAR(3)` → `VARCHAR(3)` to match JPA mapping. Acceptable here because V1 hasn't been applied to any environment yet — would be V2 forward-only otherwise.

**Senior lesson**: JPA `@Column(length=N)` generates VARCHAR(N) by default. If your DDL uses CHAR, schema validation fails. Either match the entity (`columnDefinition = "CHAR(3)"`) or match the DDL. For ISO 4217 currency, both work — VARCHAR is more permissive and slightly larger but easier to evolve.

---

## 10. Production Gaps Documented

What we **did not** build in L9, that real production would add:

| Gap | Why deferred |
|---|---|
| Real payment-service (6th microservice) | PaymentMockService stands in. Easy to extract later — the topics and event contracts are already defined. |
| Outbox pattern in order-service / inventory-service | L9 publishes Kafka events directly via KafkaTemplate without transactional outbox. Risk: state committed but Kafka send fails. Fix: copy product-service L6 outbox pattern. |
| DLT (dead letter topic) for unprocessable saga events | L6 hw Q1 solution applies here too. Saga events that can't be processed (DB down, poison message) currently retry forever. |
| Saga timeout / global TTL | We have per-reservation TTL but no overall saga timeout. An order stuck in PENDING_PAYMENT for hours should auto-cancel. |
| Distributed tracing via OpenTelemetry | L7's CorrelationIdFilter gives us a single ID; for saga visualization we want full span trees. L8 hw Q5 solution applies. |
| Idempotency at payment-service | Real payments must be idempotent by Idempotency-Key header. Our mock isn't. |
| `inventory_reservation` foreign key to a non-existent orders table | Inventory doesn't know about orders; we just store the orderId as a Long. This is correct — bounded-context boundary respected. |

---

## 11. Interview Prep Q&A

### Q1 — Why not use 2PC across services?

> 2PC's blocking semantic kills availability — a coordinator crash mid-protocol leaves participants in "in-doubt" state until the coordinator recovers. Microservices teams own their own databases and don't share a transaction manager. The latency penalty of 4× round-trips per transaction is also brutal. Saga's local-transactions + compensation pattern accepts eventual consistency in exchange for autonomy and availability.

### Q2 — Choreography or orchestration for our checkout saga?

> Choreography for linear sagas like checkout — fewer moving parts and no central coordinator to maintain. For complex multi-branch workflows where one service's failure has cascading conditional handling, orchestration's central state machine view is worth the operational complexity. The trade-off is mostly about debugging: choreography sagas require distributed tracing to follow; orchestration sagas have a queryable state machine.

### Q3 — How do you handle the inventory oversell race?

> Atomic conditional UPDATE: `UPDATE inventories SET reserved_stock = reserved_stock + :qty WHERE product_id = :id AND stock_quantity - reserved_stock >= :qty`. The arithmetic check happens inside the row lock, so check-and-update are a single atomic operation. Two concurrent reservers cannot both succeed — the second sees zero affected rows and we treat that as InsufficientStockException.

### Q4 — Why state-machine guard instead of processed_events for saga idempotency?

> Order status is the natural dedup signal for state transitions. If handleStockReserved sees an order already in PENDING_PAYMENT, it ignores the event — exactly the behavior we want for redelivery. processed_events makes sense when the processing doesn't have an obvious state to guard on (e.g. just creating a database row); for state machines the status field IS the dedup key. Cheaper and clearer.

### Q5 — How do you compensate when payment fails after reservation?

> Order-service publishes CompensateReservationEvent on order-events. Inventory's OrderEventListener consumes it and calls ReservationService.releaseForOrder which transitions ACTIVE reservations to RELEASED and decrements reserved_stock. The release_reason field captures the payment failure cause for forensics. Compensation is naturally idempotent — releaseForOrder filters by status=ACTIVE so a redelivered event finds no work to do.

### Q6 — What about the TTL reaper for abandoned orders?

> ACTIVE reservations have a 15-minute TTL by default. The TTL reaper runs every minute, queries expired-ACTIVE reservations, and releases each one in its own transaction so a partial sweep failure doesn't lose progress on already-released items. The release_reason is set to 'TTL_EXPIRED' for forensics. State-machine race guards prevent the reaper from racing the payment-success handler — whoever transitions first wins, the loser sees affected_rows=0 and skips.

### Q7 — Saga payment race — what's the lesson?

> Don't have multiple consumers of the same event drive state transitions that depend on each other. The trap is having PaymentService listen to StockReservedEvent directly while OrderService also listens to it: PaymentService can publish PaymentChargedEvent BEFORE OrderService has transitioned to PENDING_PAYMENT, and the state guard then rejects the event. The fix is to introduce a *Requested* event published AFTER the state transition: PaymentRequestedEvent kicks off payment processing only AFTER the order is ready to receive payment outcomes.

---

## 12. Homework / Reflection

完 lesson 之前自問 (解答喺 L10 開始時 fold 入 collapsible block):

1. **Outbox pattern for OrderService.create** — currently we save the Order entity then publish OrderCreatedEvent in the same `@Transactional` method. If the Kafka send fails after the DB commit, we have a lost event. Implement the outbox pattern: persist OutboxEvent in the same transaction, then a separate poller drains it to Kafka. Trade-off: latency vs reliability.

2. **Saga timeout** — order stuck in PENDING_PAYMENT for an hour is broken. Design a scheduler that cancels orders in that state past some threshold and publishes CompensateReservationEvent. What's the right threshold? What if the timeout fires but the payment actually succeeded right after?

3. **Apply CB + Retry to inventory-service's outbound calls** — currently inventory-service doesn't make outbound HTTP calls, but in production it might call a warehouse-management-system API to verify shelf locations. Sketch the wrapper service + fallback design.

4. **`@KafkaHandler` for type-safe dispatch** — replace the `ConsumerRecord<String, Object>` + instanceof pattern with class-level @KafkaListener + @KafkaHandler methods, one per event type. Pros / cons vs the current approach?

5. **Per-item partial reservation** — currently a single insufficient-stock item fails the WHOLE order. Real e-commerce sometimes allows partial fulfillment with user notification. Sketch the schema + saga changes needed to support "ship what's available, refund the rest."

<details>
<summary><strong>📖 Polished Solutions (L10 session fold-back)</strong></summary>

> 小V 嘅 cold-attempt notes (final score 15/180 = 8%):
> - **Q2 (15/30)**: Threshold direction 啱 (30min, defensible for B2C checkout). Senior gem: 自發 question "係唔係只能退款 because order cancelled" — 直接 catch 到 saga timeout 嘅 late-arriving payment race，呢個正係 staff-level engineer 嘅 instinct。但唔 aware of 兩個 alternative production pattern (two-phase probe + cancel-via-payment-API)。
> - **Q1 / Q3 / Q4 / Q5 (0/150)**: Cold surrender — L9 hw 全部 production hands-on topic (outbox saga reliability, distributed CB extension, type-safe Kafka dispatch, partial fulfillment business logic)。冇做過 production saga 嘅人 cold-attempt 答唔到合理。
> - 整體分數 L6 35→L7 26→L8 0→L9 15 — Q2 reverse 咗 L8 嘅 surrender，因為 saga timeout race 同 L6 嘅 transient-vs-permanent / L7 嘅 stale-cache 同一 family — 你開始 build production-incident instinct。

---

### Q1 — Outbox Pattern for `OrderService.create`

#### The dual-write problem

```java
@Transactional
public Order create(...) {
    Order saved = orderRepo.save(order);                        // DB commit OK
    kafkaTemplate.send(orderEventsTopic, ..., event);            // ⚠️ what if this fails?
    return saved;
}
```

兩個 system (MySQL + Kafka) 嘅 commit 唔可以 atomic — Spring `@Transactional` 只 cover MySQL。場景：

| 失敗 sequence | 結果 |
|---|---|
| DB commit fails before send | Rollback, send doesn't happen — clean ✅ |
| Send fails before DB commit | Transaction rolls back, no event sent — clean ✅ |
| **DB commits → JVM crashes before send** | **Order persisted but event lost — saga stuck forever 🔥** |
| **DB commits → Kafka broker down → send fails** | **Same as above** |

第 3-4 種失敗係 **silent corruption** — 你 metrics 唔會 alarm，order 永遠 stuck 喺 PENDING_INVENTORY，要 manual SRE intervention。

#### Outbox pattern: same-transaction durability

> 將 event 寫入 **同一個 DB transaction**，再由 separate poller 將 outbox row drain 去 Kafka。Atomicity 由 MySQL transaction 保證。

L6 product-service 已落地 — reference 嘅 OutboxEvent + OutboxPoller 直接 copy。

```sql
-- V2 migration for order-service
CREATE TABLE outbox_event (
    id              BIGINT PRIMARY KEY,                     -- Snowflake
    aggregate_id    VARCHAR(64) NOT NULL,                   -- order ID for partitioning
    event_type      VARCHAR(64) NOT NULL,
    payload         JSON NOT NULL,                          -- serialized event
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING / PUBLISHED / FAILED
    created_at      DATETIME(6) NOT NULL,
    published_at    DATETIME(6) NULL,
    retry_count     INT NOT NULL DEFAULT 0,

    KEY idx_outbox_status_created (status, created_at)      -- poller scan
);
```

#### Refactored OrderService.create

```java
@Transactional
public Order create(Long userId, CreateOrderRequest req, String idempotencyKey) {
    // ... idempotency check + entity build (unchanged) ...
    Order saved = orderRepo.save(order);

    // Persist event to outbox INSIDE the same transaction.
    OrderCreatedEvent event = new OrderCreatedEvent(...);
    outboxRepo.save(OutboxEvent.builder()
        .id(snowflake.nextId())
        .aggregateId(String.valueOf(saved.getId()))
        .eventType("OrderCreated")
        .payload(objectMapper.writeValueAsString(event))
        .status(PENDING)
        .build());

    return saved;
    // No more kafkaTemplate.send() here.
}
```

#### Poller

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {
    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:200}")
    public void drain() {
        List<OutboxEvent> pending = outboxRepo.findTop100ByStatusOrderByCreatedAtAsc(PENDING);
        for (OutboxEvent e : pending) {
            try {
                kafkaTemplate.send(topicFor(e.getEventType()),
                                   e.getAggregateId(), e.getPayload())
                             .get();                              // synchronous — fail loudly
                e.setStatus(PUBLISHED);
                e.setPublishedAt(Instant.now());
                outboxRepo.save(e);
            } catch (Exception ex) {
                e.setRetryCount(e.getRetryCount() + 1);
                if (e.getRetryCount() >= 10) e.setStatus(FAILED);
                outboxRepo.save(e);
                log.error("Outbox publish failed for id={}", e.getId(), ex);
            }
        }
    }
}
```

#### Trade-off

| Property | Without outbox | With outbox |
|---|---|---|
| **Atomicity** | None — dual write | DB transaction ✅ |
| **Latency to Kafka** | < 5ms | 100-500ms (poll interval) |
| **Lost events under crash** | possible | impossible ⭐ |
| **Operational complexity** | low | +1 table + 1 poller + monitoring |
| **Throughput** | bottlenecked by Kafka send | bottlenecked by poller batch size |

#### What about CDC alternative (Debezium)?

Debezium watches MySQL binlog and streams changes to Kafka — no poller code needed. Trade-off:

- Pros: zero-application-code, lowest latency (~10ms binlog tail), exactly-once semantics with Kafka Connect
- Cons: heavy infra (Kafka Connect cluster), schema registry needed, harder local dev
- When: 100+ services with shared CDC infra. For our scale, in-app poller is right.

#### 🎯 Interview金句

> "Spring's @Transactional only covers a single resource — MySQL. Dual-writing to MySQL and Kafka in the same method is a silent reliability hole; a crash between the DB commit and the Kafka send loses the event. The outbox pattern brings the Kafka write back into the transaction by persisting the event payload to a DB table inside the same transaction, then a separate poller drains the outbox to Kafka. We accept ~200ms latency in exchange for zero lost events."

---

### Q2 — Saga Timeout + Late-Arriving Payment Race

#### Two design decisions嘅 surface

1. **What threshold?** depends on use case
2. **What happens when timeout fires AFTER payment actually succeeded?** ← the trap

#### Threshold mental model

| Use case | Threshold | 點解 |
|---|---|---|
| **User-facing checkout** | 5-15 min | User UX — confirmation page can't wait |
| **B2B order processing** | 30-60 min | Batch ok, less time-sensitive |
| **Crypto / fraud-sensitive** | 60s - 2min | High-risk window must be short |
| **Subscription renewal** | 24 hr | User not waiting at all |

L9 checkout: **10 minutes**. Default for B2C.

#### Scheduler implementation

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaTimeoutReaper {
    private final OrderRepository orderRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.saga.timeout-minutes:10}")
    private long timeoutMinutes;

    @Scheduled(fixedDelayString = "${app.saga.reaper-interval-ms:60000}")
    public void reapStuckOrders() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
        List<Order> stuck = orderRepo.findStuckSince(
            List.of(PENDING_INVENTORY, PENDING_PAYMENT), cutoff);

        for (Order order : stuck) {
            try {
                timeoutSingleOrder(order);                       // own transaction
            } catch (Exception e) {
                log.error("Timeout reaper failed for orderId={}", order.getId(), e);
            }
        }
    }

    @Transactional
    protected void timeoutSingleOrder(Order order) {
        // Re-read inside transaction to dodge race with concurrent saga handler.
        Order fresh = orderRepo.findById(order.getId()).orElse(null);
        if (fresh == null || fresh.getStatus() == CONFIRMED || fresh.getStatus() == CANCELLED) {
            return;                                              // already terminal
        }

        OrderStatus previousState = fresh.getStatus();
        fresh.cancel("SAGA_TIMEOUT: stuck in " + previousState + " > " + timeoutMinutes + "min");
        orderRepo.save(fresh);

        if (previousState == PENDING_PAYMENT) {
            // Stock was reserved — compensate.
            kafkaTemplate.send(orderEventsTopic, String.valueOf(fresh.getId()),
                new CompensateReservationEvent(UUID.randomUUID(), Instant.now(),
                    fresh.getId(), "SAGA_TIMEOUT"));
        }
        // If PENDING_INVENTORY, nothing was reserved — no compensation needed.
        log.warn("Order TIMEOUT_CANCELLED orderId={} from {} after {}min",
            fresh.getId(), previousState, timeoutMinutes);
    }
}
```

#### The race: late-arriving PaymentChargedEvent

```
T=10:00:00  Order PENDING_PAYMENT
T=10:00:01  Payment service is slow
T=10:10:00  Timeout reaper fires:
              Order → CANCELLED
              Publish CompensateReservationEvent
              Inventory reservation → RELEASED
T=10:10:02  PaymentChargedEvent arrives (payment finally succeeded)
            order-service.handlePaymentCharged:
              Status check: order is CANCELLED, not PENDING_PAYMENT → IGNORED
            But: 💳 user IS charged, 📦 inventory IS released
            Outcome: user paid but no shipment
```

呢個 race 喺 production 一定會撞 — 唔係「會唔會」嘅問題，係「幾耐撞一次」。三個 patterns 處理：

#### Pattern A: Refund flow (most common)

```java
@Transactional
public void handlePaymentCharged(Long orderId) {
    Order order = orderRepo.findById(orderId).orElse(null);
    if (order == null) return;

    if (order.getStatus() == CANCELLED && order.getCancelReason().startsWith("SAGA_TIMEOUT")) {
        // Late-arriving charge against timed-out order. Refund + log incident.
        log.error("Late PaymentChargedEvent for cancelled orderId={} — refunding", orderId);
        kafkaTemplate.send(refundRequestsTopic, String.valueOf(orderId),
            new RefundRequestedEvent(UUID.randomUUID(), Instant.now(),
                orderId, order.getTotalAmountCents(), order.getCurrency(),
                "LATE_PAYMENT_AFTER_TIMEOUT"));
        return;
    }

    if (order.getStatus() != PENDING_PAYMENT) return;            // other transitions
    order.setStatus(CONFIRMED);
    orderRepo.save(order);
}
```

Pros: simple, captures real $$$ flow. Cons: user got charged then refunded — bad UX (statement shows transient charge).

#### Pattern B: Two-phase timeout

```java
PENDING_PAYMENT → (timeout 10min) → PENDING_PAYMENT_TIMEOUT_PROBE
                                 ↓ (30s grace)
                                 ↓
                               either:
                                 PaymentCharged arrives → CONFIRMED (saved at the wire!)
                                 OR
                                 grace expires → CANCELLED + Compensate
```

Add a fifth state `PENDING_PAYMENT_TIMEOUT_PROBE`. Reaper transitions to PROBE first, schedule another job 30s later to finalize. Pros: catches close-to-timeout payments. Cons: extra state, extra job, 30s extra payment-stuck window.

#### Pattern C: Cancel-via-payment-API (most production-correct)

```java
@Transactional
protected void timeoutSingleOrder(Order order) {
    // Step 1: Tell payment-service to cancel the payment intent.
    // This is idempotent — succeeds if payment is pending, FAILS if already charged.
    try {
        paymentService.cancelPaymentIntent(order.getId());
    } catch (PaymentAlreadyChargedException e) {
        // Payment succeeded between our timeout decision and our cancel call.
        // Don't cancel the order — let the saga continue normally.
        log.warn("Payment already charged at timeout for orderId={} — saga continues",
            order.getId());
        return;
    }

    // Step 2: Only NOW transition to CANCELLED and publish compensation.
    order.cancel("SAGA_TIMEOUT");
    orderRepo.save(order);
    kafkaTemplate.send(orderEventsTopic, ..., new CompensateReservationEvent(...));
}
```

Pros: no charge happens at all if we cancel in time. Cons: requires payment-service to support `cancel(intentId)` idempotently — Stripe / Adyen do, in-house may not.

#### Production recommendation

For our L9 mock saga: **Pattern A (Refund)** — easiest, captures most-common case, fits the choreography pattern. Real production with Stripe: **Pattern C** — cancel at the source if possible, fall through to refund if charge already happened.

#### 🎯 Interview金句

> "Saga timeout is straightforward — schedule a reaper to cancel orders stuck past threshold. The senior question is what happens when timeout fires but the payment actually succeeded right after. Three patterns: refund flow (late charge against CANCELLED order triggers automatic refund), two-phase timeout (probe state gives one more grace window), or cancel-via-payment-API (call Stripe's idempotent cancel first, fall through to refund only if already charged). Production usually combines them: try cancel first, fall back to refund. The bug to avoid is leaving the order CANCELLED while the charge happened — that's a missing refund on the user's statement."

---

### Q3 — CB + Retry on inventory-service Outbound (WMS API)

#### Hypothetical: inventory-service calls Warehouse Management System

Real e-commerce inventory needs to know **physical shelf location** for fulfillment routing. inventory-service makes outbound HTTP to a WMS:

```
inventory-service.reserve(productId, qty)
   ├─ atomic UPDATE inventories
   ├─ INSERT inventory_reservation
   └─ HTTP POST WMS /shelf-lookup → confirm shelf has the SKU
       │
       (WMS is a 3rd-party SaaS — slow, occasionally flaky)
```

#### Why this calls for CB

WMS slowness 同 inventory 反應 latency 直接掛鉤 — without CB, WMS downtime cascade 到 inventory-service Tomcat thread exhaust, cascade 到 cart-service Feign waiting on inventory.

#### ResilientWmsClient design

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ResilientWmsClient {
    private final WmsClient wmsClient;
    private final InventoryReservationRepository reservationRepo;

    @CircuitBreaker(name = "wmsClient", fallbackMethod = "verifyShelfFallback")
    @Retry(name = "wmsClient")
    public ShelfInfo verifyShelf(Long productId) {
        return wmsClient.lookup(productId);
    }

    /**
     * KEY DIFFERENCE from cart-side CB fallbacks: inventory-service can
     * proceed with degraded info and flag the reservation for later
     * manual review. Cart had to refuse (correctness > availability) but
     * inventory can defer correctness check downstream.
     */
    public ShelfInfo verifyShelfFallback(Long productId, Throwable cause) {
        log.warn("WMS unavailable for productId={} cause={} — proceeding with UNKNOWN shelf, "
                + "reservation flagged for manual review",
            productId, cause.getClass().getSimpleName());
        return ShelfInfo.unknownLocation(productId);              // marker value
    }
}
```

#### The senior tweak — flag reservations for manual review

```java
// In ReservationService.reserve(...):
ShelfInfo shelf = resilientWmsClient.verifyShelf(productId);
boolean shelfUnknown = shelf.isUnknown();

InventoryReservation reservation = InventoryReservation.builder()
    .productId(productId)
    // ... other fields ...
    .needsManualReview(shelfUnknown)                            // ← new column
    .reviewReason(shelfUnknown ? "WMS_UNAVAILABLE" : null)
    .build();
```

Then a daily report flags `needs_manual_review=true` reservations — operations team verifies the inventory exists physically before shipment. **Degraded mode + observability is better than blocking** when downstream is slow.

#### Why this DIFFERS from cart-side CB fallback

| Layer | Failure mode |
|---|---|
| **Cart → inventory** | Refuse (correctness > availability) — cart can't allow oversell |
| **Inventory → WMS** | Proceed-and-flag (availability > correctness) — order can ship later if shelf actually has it; manual review catches the edge |

**Senior lesson**: degraded mode 嘅 acceptability 由 **downstream call 嘅 semantic** 決定。Cart-inventory 係 hard correctness boundary; inventory-WMS 係 optimization signal。

#### 🎯 Interview金句

> "Not every CB fallback should refuse. Cart's call to inventory must refuse on outage because the stock check is a hard correctness boundary — we can't allow oversells. Inventory's call to WMS is different — the shelf-lookup is an optimization, not a correctness boundary. We can proceed with an UNKNOWN-shelf marker, flag the reservation for manual review, and accept the operational cost. The principle is: classify the downstream call as hard-correctness or soft-optimization, and pick fallback accordingly."

---

### Q4 — `@KafkaHandler` for Type-Safe Dispatch

#### Current pattern (L9 code) — instanceof switch

```java
@KafkaListener(topics = "${app.kafka.topic.order-events}", groupId = "...")
public void onOrderEvent(ConsumerRecord<String, Object> record, Acknowledgment ack) {
    Object event = record.value();
    if (event instanceof OrderCreatedEvent created) {
        handle(created);
    } else if (event instanceof CompensateReservationEvent compensate) {
        handleCompensation(compensate);
    } else {
        log.debug("Unknown event type: {}", event.getClass());
    }
    ack.acknowledge();
}
```

Works, but:
- No compile-time check that you handled all event types
- `instanceof` chain grows linearly with event types
- Hard to apply different concurrency / error handling per event type
- Mixed concerns in one method body

#### Refactored — class-level @KafkaListener + @KafkaHandler

```java
@Component
@KafkaListener(topics = "${app.kafka.topic.order-events}",
               groupId = "inventory-service-saga",
               containerFactory = "kafkaListenerContainerFactory")
@RequiredArgsConstructor
@Slf4j
public class OrderEventListener {

    private final ReservationService reservationService;

    @KafkaHandler
    public void onOrderCreated(OrderCreatedEvent event, Acknowledgment ack) {
        log.info("OrderCreated orderId={}", event.orderId());
        // ... reserve stock ...
        ack.acknowledge();
    }

    @KafkaHandler
    public void onCompensation(CompensateReservationEvent event, Acknowledgment ack) {
        log.info("Compensation orderId={}", event.orderId());
        // ... release reservation ...
        ack.acknowledge();
    }

    @KafkaHandler(isDefault = true)
    public void onUnknown(@Payload Object event, Acknowledgment ack) {
        log.warn("Unknown event type on order-events: {}", event.getClass());
        ack.acknowledge();
    }
}
```

#### Pros / cons

| Aspect | instanceof | @KafkaHandler |
|---|---|---|
| **Type safety** | runtime check | compile-time match |
| **Adding new event type** | edit method, recompile, test | add new @KafkaHandler method, recompile |
| **Default handler** | implicit (else branch) | explicit `isDefault=true` |
| **Per-handler config** | impossible (single method) | possible (different @Async, error handlers) |
| **Shared state across handlers** | easy (in same method) | medium (class-level fields) |
| **Migration cost** | n/a | ~30 min for our 2 listener classes |

#### Caveats

- `@KafkaListener` must be on the **class**, not the methods. Existing code with method-level @KafkaListener needs full restructure.
- `@KafkaHandler` requires the producer-side to set the `__TypeId__` header correctly — same trustedPackages constraint as before. If the type header is missing, ALL events route to `isDefault=true` handler.
- Multiple @KafkaListener on the same class is allowed — useful when one class consumes multiple topics. Each topic group has its own @KafkaHandler resolution.

#### Should we refactor L9?

**Recommended yes** — type safety + cleaner per-handler error policies are worth the 30min refactor. The migration is mechanical and the tests verify correctness.

#### 🎯 Interview金句

> "`@KafkaHandler` gives you compile-time event-type dispatch instead of runtime instanceof. The class becomes a multi-method handler where Spring routes based on the deserialized payload's class. Pros are type safety and per-handler configuration. Cons are class-level @KafkaListener rigidity and the same trustedPackages dependency — if the type header is wrong, everything routes to the default handler. We'd refactor L9's two listener classes to this pattern for the next iteration."

---

### Q5 — Per-Item Partial Reservation

#### The business problem

User orders 5 items. Inventory has 3 of them, missing 2. Real e-commerce sometimes wants:
- Ship the 3 available items now
- Mark the 2 unavailable as backorder OR cancelled with notification
- User pays only for what ships

L9 implementation: any insufficient-stock item fails the WHOLE order (all-or-nothing). Production e-commerce 通常 prefer partial fulfillment for UX.

#### Schema changes

```sql
-- OrderItem gets per-item status
ALTER TABLE order_items
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING / FULFILLED / UNAVAILABLE / BACKORDERED
    ADD COLUMN unavailable_reason VARCHAR(64) NULL,
    ADD COLUMN backorder_eta DATETIME(6) NULL;

-- Order's total becomes "ordered total" with a "fulfilled total" separately tracked
ALTER TABLE orders
    ADD COLUMN fulfilled_amount_cents BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN partial_fulfillment BOOLEAN NOT NULL DEFAULT FALSE;
```

#### New saga events

```java
// Inventory publishes this instead of all-or-nothing
public record StockPartiallyReservedEvent(
    UUID eventId, Instant occurredAt,
    Long orderId, Long userId,
    List<ReservedItem> reserved,                                 // succeeded
    List<UnavailableItem> unavailable,                          // failed
    Long fulfillableAmountCents, String currency
) implements DomainEvent {
    public record ReservedItem(Long productId, Integer quantity) {}
    public record UnavailableItem(Long productId, Integer requestedQty, Integer availableQty) {}
}
```

#### Saga changes

```java
// inventory-service: reserve items independently
public void handle(OrderCreatedEvent event) {
    List<ReservedItem> reserved = new ArrayList<>();
    List<UnavailableItem> unavailable = new ArrayList<>();

    for (Item item : event.items()) {
        try {
            reservationService.reserve(item.productId(), event.userId(),
                event.orderId(), item.quantity());
            reserved.add(new ReservedItem(item.productId(), item.quantity()));
        } catch (InsufficientStockException e) {
            unavailable.add(new UnavailableItem(item.productId(),
                item.quantity(), e.getAvailableQty()));
        }
    }

    if (reserved.isEmpty()) {
        // Nothing reservable — original saga path (all-failed cancellation).
        publishStockReservationFailedEvent(event);
        return;
    }

    long fulfillableAmount = reserved.stream()
        .mapToLong(r -> findItemPrice(event, r.productId()) * r.quantity())
        .sum();
    publishStockPartiallyReservedEvent(event, reserved, unavailable, fulfillableAmount);
}
```

#### Order-service handler updates

```java
@Transactional
public void handlePartialReservation(StockPartiallyReservedEvent event) {
    Order order = orderRepo.findById(event.orderId()).orElseThrow();
    if (order.getStatus() != PENDING_INVENTORY) return;

    // Mark each item's status
    for (OrderItem item : order.getItems()) {
        boolean isReserved = event.reserved().stream()
            .anyMatch(r -> r.productId().equals(item.getProductId()));
        item.setStatus(isReserved ? FULFILLED : UNAVAILABLE);
        if (!isReserved) {
            item.setUnavailableReason("INSUFFICIENT_STOCK");
        }
    }

    order.setFulfilledAmountCents(event.fulfillableAmountCents());
    order.setPartialFulfillment(true);
    order.setStatus(PENDING_PAYMENT);
    orderRepo.save(order);

    // Charge only fulfilled amount.
    kafkaTemplate.send(paymentRequestsTopic, String.valueOf(order.getId()),
        new PaymentRequestedEvent(UUID.randomUUID(), Instant.now(),
            order.getId(), event.fulfillableAmountCents(), event.currency()));

    // Notify user of partial fulfillment.
    kafkaTemplate.send(notificationsTopic, String.valueOf(order.getUserId()),
        new PartialFulfillmentNoticeEvent(...));
}
```

#### UX challenges

1. **User consent** — POS / frontend needs a "Allow partial fulfillment?" checkbox at checkout. Without it, user expects all-or-nothing.
2. **Re-confirmation flow** — some retailers prefer "we see 2 items unavailable, do you want to proceed with just 3?" — adds an extra HTTP round-trip but better UX.
3. **Backorder vs cancel** — `BACKORDERED` status means "ship later"; needs ETA + supply-chain integration. Easier path: cancel unavailable items + notify user.
4. **Refund for already-charged** — if charge already happened for ordered total and we discover unavailable post-fact, need refund-the-difference flow.

#### Complexity vs benefit

L9 happy path → partial fulfillment changes:
- +2 schema fields per item
- +2 schema fields per order
- New event type (StockPartiallyReservedEvent)
- 2 new handler methods
- New notification event + topic
- UX flow changes

Probably ~+300 LOC + 1 new saga branch. Worth it only when the business clearly benefits from partial sales (Amazon does, niche luxury retailer doesn't).

#### 🎯 Interview金句

> "Partial fulfillment is more business logic than distributed systems — but it touches the saga in non-trivial ways. Each order item gets its own status (FULFILLED / UNAVAILABLE / BACKORDERED). The inventory side publishes StockPartiallyReservedEvent with per-item outcomes instead of all-or-nothing. The order's total splits into ordered_amount vs fulfilled_amount, and payment charges only the fulfilled portion. Big UX implications: user needs upfront consent for partial, then a notification when partial actually happens. The senior judgment call is whether the business needs this — Amazon yes, boutique retailer no — because the implementation cost is real."

---

#### 🎯 Synthesis — Q1-Q5 之間嘅 link

| Question | Connects to L9 codebase |
|---|---|
| Q1 (Outbox) | 修補 OrderService.create 嘅 dual-write reliability hole — same pattern L6 product-service 已用 |
| Q2 (Saga timeout) | 補完 saga 嘅 stuck-in-state recovery — TTL reaper already exists for reservation,呢個係 order-level version |
| Q3 (CB on inventory outbound) | Extend L8 ResilientProductClient pattern + 教 hard-correctness vs soft-optimization fallback distinction |
| Q4 (`@KafkaHandler`) | Refactor L9 嘅 4 個 instanceof switch listener 成 type-safe dispatch — quick win |
| Q5 (Partial fulfillment) | Business-logic saga branch — 點 evolve choreography when "all-or-nothing" becomes "partial OK" |

呢 5 條題目連起嚟 = **「Saga 由 happy-path demo → production-grade resilience + business flexibility」嘅 staff-level checklist**。L9 codebase 落地咗 baseline (state machine + atomic UPDATE + compensation + 5 integration tests)，呢 5 條答案就係剩低 80% 嘅 production hardening — dual-write atomicity、stuck-saga recovery、distributed CB extension、type-safe dispatch、partial fulfillment business logic。

</details>

---

## 13. Next Lesson Preview — Lesson 10

**L10 — API Gateway: Spring Cloud Gateway**

- Single entry point for all 5 services
- Route rules (YAML or Java DSL)
- JWT validation at the gateway (eliminates per-service oauth2 resource server config?)
- Rate limiting (Redis-backed)
- CORS
- Why we don't use Eureka / Spring Cloud Config (explained)
- Gateway sits in front of: user, product, inventory, cart, order

**Reinforce points** L9 留低嘅:
- HW Q1 (outbox pattern) becomes critical when gateway introduces new failure modes
- HW Q4 (KafkaHandler) is L10 cleanup opportunity

---

## References

- Microsoft Azure Architecture Center — Saga design pattern: https://learn.microsoft.com/en-us/azure/architecture/reference-architectures/saga/saga
- Chris Richardson — *Microservices Patterns* (Manning, 2018) chapter 4: Managing transactions with sagas
- Pat Helland — *Life Beyond Distributed Transactions* (HPTS 2007 / 2017 update)
- Spring Kafka — JsonDeserializer trusted packages: https://docs.spring.io/spring-kafka/reference/serdes.html
- Resilience4j Spring Boot 3: https://resilience4j.readme.io/docs/spring-boot-3
- Apache Kafka Choreography docs / patterns
