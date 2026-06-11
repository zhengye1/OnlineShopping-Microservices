package com.onlineshopping.order.saga;

import com.onlineshopping.order.AbstractIntegrationTest;
import com.onlineshopping.order.entity.Order;
import com.onlineshopping.order.entity.OrderStatus;
import com.onlineshopping.order.event.StockReservationFailedEvent;
import com.onlineshopping.order.event.StockReservedEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L9 end-to-end saga participation tests for order-service.
 *
 * <p>Test strategy: order-service is the System Under Test. inventory-service
 * is simulated by a test-side {@link org.springframework.kafka.core.KafkaTemplate}
 * publishing {@link StockReservedEvent} / {@link StockReservationFailedEvent}
 * directly to the inventory-saga-events topic. PaymentMockService runs as the
 * real component inside the Spring context — it consumes the synthetic
 * StockReservedEvent and produces the payment outcome, exercising the full
 * order-service saga without needing inventory-service running.
 *
 * <p>Tests use Awaitility to bridge from synchronous HTTP POST + asynchronous
 * Kafka event propagation through to the final order state.
 *
 * <p>Payment threshold lowered to 100000 cents = $1000 — orders larger than
 * this fail at payment, exercising the compensation flow.
 */
@TestPropertySource(properties = "app.payment.fail-above-cents=100000")
class OrderSagaIntegrationTest extends AbstractIntegrationTest {

    private static final long TEST_USER_ID = 42L;
    private static final long TEST_PRODUCT_ID = 100L;

    @Value("${app.kafka.topic.inventory-saga-events}")
    private String inventorySagaTopic;

    @Value("${app.kafka.topic.payment-events}")
    private String paymentTopic;

    @Value("${app.kafka.topic.order-events}")
    private String orderEventsTopic;

    @Test
    void postOrder_returns201AndPendingInventoryStatus() throws Exception {
        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = """
                {
                  "items": [
                    {"productId": %d, "quantity": 2, "priceAtOrderCents": 5000}
                  ],
                  "currency": "CAD"
                }
                """.formatted(TEST_PRODUCT_ID);

        mockMvc.perform(post("/orders")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_INVENTORY"))
                .andExpect(jsonPath("$.totalAmountCents").value(10000))
                .andExpect(jsonPath("$.currency").value("CAD"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void saga_happyPath_stockReserved_thenPaymentCharged_orderConfirmed() throws Exception {
        // 1. POST /orders → Order PENDING_INVENTORY
        Long orderId = postOrderAndExtractId(5000L, 2);  // total = 10000 cents — below 100000 threshold

        // 2. Simulate inventory-service publishing StockReservedEvent.
        publishStockReserved(orderId, 10000L, "CAD");

        // 3. Wait for the full saga to complete:
        //    StockReservedEvent → handleStockReserved (PENDING_INVENTORY → PENDING_PAYMENT)
        //    PaymentMockService → PaymentChargedEvent (totalCents below threshold)
        //    handlePaymentCharged (PENDING_PAYMENT → CONFIRMED)
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Order o = orderRepo.findById(orderId).orElseThrow();
                    assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                });
    }

    @Test
    void saga_paymentFailure_compensationFires_orderCancelled() throws Exception {
        // 1. POST /orders with total = 200000 cents — above 100000 threshold → payment will fail.
        Long orderId = postOrderAndExtractId(50000L, 4);  // 200000 cents

        // 2. Simulate inventory-service publishing StockReservedEvent.
        publishStockReserved(orderId, 200000L, "CAD");

        // 3. Wait for the compensation path to complete:
        //    StockReservedEvent → PENDING_PAYMENT
        //    PaymentMockService publishes PaymentFailedEvent (above threshold)
        //    handlePaymentFailed: order CANCELLED + CompensateReservationEvent
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Order o = orderRepo.findById(orderId).orElseThrow();
                    assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
                    assertThat(o.getCancelReason()).contains("PAYMENT_FAILED");
                });
    }

    @Test
    void saga_stockUnavailable_orderCancelled_noCompensation() throws Exception {
        // 1. POST /orders
        Long orderId = postOrderAndExtractId(5000L, 1);

        // 2. Simulate inventory-service unable to reserve.
        StockReservationFailedEvent failed = new StockReservationFailedEvent(
                UUID.randomUUID(), Instant.now(), orderId, "INSUFFICIENT_STOCK");
        kafkaTemplate.send(inventorySagaTopic, String.valueOf(orderId), failed);

        // 3. Wait for: handleStockReservationFailed (PENDING_INVENTORY → CANCELLED)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Order o = orderRepo.findById(orderId).orElseThrow();
                    assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
                    assertThat(o.getCancelReason()).contains("STOCK_UNAVAILABLE");
                });
    }

    @Test
    void postOrder_idempotencyKey_secondCallReturnsSameOrder() throws Exception {
        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = """
                {
                  "items": [{"productId": %d, "quantity": 1, "priceAtOrderCents": 5000}],
                  "currency": "CAD"
                }
                """.formatted(TEST_PRODUCT_ID);
        String key = "test-idempotency-" + UUID.randomUUID();

        // First POST — creates a new order.
        var first = mockMvc.perform(post("/orders")
                        .header("Authorization", authHeader)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Long firstId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("id").asLong();

        // Second POST with same key — must return the same order id.
        var second = mockMvc.perform(post("/orders")
                        .header("Authorization", authHeader)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Long secondId = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("id").asLong();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(orderRepo.count()).isEqualTo(1);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private Long postOrderAndExtractId(long unitPriceCents, int quantity) throws Exception {
        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = """
                {
                  "items": [{"productId": %d, "quantity": %d, "priceAtOrderCents": %d}],
                  "currency": "CAD"
                }
                """.formatted(TEST_PRODUCT_ID, quantity, unitPriceCents);

        var result = mockMvc.perform(post("/orders")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asLong();
    }

    private void publishStockReserved(Long orderId, Long totalCents, String currency) {
        StockReservedEvent reserved = new StockReservedEvent(
                UUID.randomUUID(), Instant.now(), orderId, TEST_USER_ID, totalCents, currency);
        kafkaTemplate.send(inventorySagaTopic, String.valueOf(orderId), reserved);
    }
}
