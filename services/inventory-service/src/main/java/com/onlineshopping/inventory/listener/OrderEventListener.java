package com.onlineshopping.inventory.listener;

import com.onlineshopping.inventory.event.CompensateReservationEvent;
import com.onlineshopping.inventory.event.OrderCreatedEvent;
import com.onlineshopping.inventory.event.StockReservationFailedEvent;
import com.onlineshopping.inventory.event.StockReservedEvent;
import com.onlineshopping.inventory.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * L9 saga step 2 — inventory side. Consumes OrderCreatedEvent from
 * order-events topic and atomically reserves stock per item. Publishes
 * StockReservedEvent on success, StockReservationFailedEvent on
 * insufficient stock.
 *
 * <p>Idempotency: reservations are deduped by (orderId, productId) lookup
 * inside ReservationService before INSERT. A redelivered OrderCreatedEvent
 * doesn't create a second reservation.
 *
 * <p>Per-item failure handling: a single insufficient-stock among multiple
 * items fails the WHOLE saga step — we publish StockReservationFailedEvent
 * and roll back any reservations already made within this transaction.
 * Saga compensation is uniform: partial reservations are forbidden.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventListener {

    private final ReservationService reservationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topic.inventory-saga-events}")
    private String inventorySagaTopic;

    @KafkaListener(topics = "${app.kafka.topic.order-events}",
            groupId = "inventory-service-saga")
    public void onOrderEvent(Object event, Acknowledgment ack) {
        if (event instanceof OrderCreatedEvent created) {
            handle(created);
        } else if (event instanceof CompensateReservationEvent compensate) {
            handleCompensation(compensate);
        } else {
            log.debug("OrderEventListener — ignoring event of type {}", event.getClass());
        }
        ack.acknowledge();
    }

    /**
     * L9 Phase 6 compensation handler. Releases any ACTIVE reservations
     * belonging to the order. Naturally idempotent — ReservationService
     * filters to status=ACTIVE, so a redelivered compensation event for
     * an already-released order is a no-op (logs warning, skips).
     *
     * <p>{@code reason} from upstream is preserved into
     * {@code inventory_reservation.release_reason} so an SRE pulling up
     * the row sees the full PAYMENT_FAILED context.
     */
    private void handleCompensation(CompensateReservationEvent event) {
        log.info("Received CompensateReservationEvent orderId={} reason={}",
                event.orderId(), event.reason());
        try {
            reservationService.releaseForOrder(event.orderId(), event.reason());
            log.info("Compensation completed for orderId={}", event.orderId());
        } catch (Exception e) {
            // Don't ack on failure — let the listener container redeliver.
            // releaseForOrder is idempotent (state-guard) so retries are safe.
            log.error("Compensation FAILED for orderId={} — will redeliver",
                    event.orderId(), e);
            throw e;
        }
    }

    private void handle(OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent orderId={} items={}",
                event.orderId(), event.items().size());
        try {
            for (OrderCreatedEvent.Item item : event.items()) {
                reservationService.reserve(item.productId(), event.userId(),
                        event.orderId(), item.quantity());
            }
            // All reservations succeeded — publish saga step success.
            StockReservedEvent reserved = new StockReservedEvent(
                    UUID.randomUUID(), Instant.now(),
                    event.orderId(), event.userId(),
                    event.totalAmountCents(), event.currency()
            );
            kafkaTemplate.send(inventorySagaTopic, String.valueOf(event.orderId()), reserved);
            log.info("Published StockReservedEvent orderId={}", event.orderId());

        } catch (ResponseStatusException e) {
            // ReservationService throws 409 on insufficient stock. Treat as
            // saga step failure → publish failure event. Note: any
            // reservations made BEFORE the throw were rolled back by the
            // @Transactional boundary in ReservationService.reserve().
            String reason = e.getReason() == null ? "INSUFFICIENT_STOCK" : e.getReason();
            log.warn("Reservation failed for orderId={} reason={}", event.orderId(), reason);
            StockReservationFailedEvent failed = new StockReservationFailedEvent(
                    UUID.randomUUID(), Instant.now(), event.orderId(), reason);
            kafkaTemplate.send(inventorySagaTopic, String.valueOf(event.orderId()), failed);
            log.info("Published StockReservationFailedEvent orderId={}", event.orderId());
        }
    }
}
