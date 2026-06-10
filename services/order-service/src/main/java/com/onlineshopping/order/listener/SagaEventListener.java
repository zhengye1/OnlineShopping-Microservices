package com.onlineshopping.order.listener;

import com.onlineshopping.order.event.PaymentChargedEvent;
import com.onlineshopping.order.event.PaymentFailedEvent;
import com.onlineshopping.order.event.StockReservationFailedEvent;
import com.onlineshopping.order.event.StockReservedEvent;
import com.onlineshopping.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * L9 saga consumer — order-service side. Consumes inventory + payment event
 * topics and drives the Order state machine via OrderService transitions.
 *
 * <p>MANUAL_IMMEDIATE ack — same pattern as inventory-service L6
 * ProductEventListener. Ack only after the state transition is committed,
 * so a crash mid-processing redelivers the event (which is idempotent due
 * to the state-guard pattern).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaEventListener {

    private final OrderService orderService;

    @KafkaListener(topics = "${app.kafka.topic.inventory-saga-events}",
            groupId = "order-service-inventory")
    public void onInventorySagaEvent(Object event, Acknowledgment ack) {
        // Spring Kafka deserializes to the @Header type-id by default; we
        // accept Object and switch on runtime type for clarity.
        if (event instanceof StockReservedEvent e) {
            log.info("Received StockReservedEvent orderId={}", e.orderId());
            orderService.handleStockReserved(e.orderId());
        } else if (event instanceof StockReservationFailedEvent e) {
            log.info("Received StockReservationFailedEvent orderId={} reason={}",
                    e.orderId(), e.reason());
            orderService.handleStockReservationFailed(e.orderId(), e.reason());
        } else {
            log.warn("Unknown inventory-saga-event type: {}", event.getClass());
        }
        ack.acknowledge();
    }

    @KafkaListener(topics = "${app.kafka.topic.payment-events}",
            groupId = "order-service-payment")
    public void onPaymentEvent(Object event, Acknowledgment ack) {
        if (event instanceof PaymentChargedEvent e) {
            log.info("Received PaymentChargedEvent orderId={} amount={} {}",
                    e.orderId(), e.amountCents(), e.currency());
            orderService.handlePaymentCharged(e.orderId());
        } else if (event instanceof PaymentFailedEvent e) {
            log.info("Received PaymentFailedEvent orderId={} reason={}",
                    e.orderId(), e.reason());
            orderService.handlePaymentFailed(e.orderId(), e.reason());
        } else {
            log.warn("Unknown payment-events type: {}", event.getClass());
        }
        ack.acknowledge();
    }
}
