package com.onlineshopping.order.service;

import com.onlineshopping.order.event.PaymentChargedEvent;
import com.onlineshopping.order.event.PaymentFailedEvent;
import com.onlineshopping.order.event.PaymentRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * L9 Payment mock — listens to inventory-saga-events for StockReservedEvent
 * and publishes a PaymentChargedEvent (or PaymentFailedEvent if the order's
 * total falls in a configured failure window).
 *
 * <p>This stands in for a real payment-service at L9 scope. Architecturally
 * it is a separate concern from order-service — the consumer group ID
 * is distinct and there is no shared transaction. Replace with a true
 * 6th microservice when payment processing actually matters.
 *
 * <p>Failure mode is controlled by {@code app.payment.fail-above-cents}.
 * Orders with totalCents > threshold fail; below threshold succeed. Lets
 * integration tests exercise the compensation path deterministically.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentMockService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topic.payment-events}")
    private String paymentTopic;

    /**
     * Orders with totalCents > this value get PaymentFailed. Default is
     * effectively disabled (Long.MAX_VALUE). Phase 6 compensation tests
     * lower this in their test profile to force the failure branch.
     */
    @Value("${app.payment.fail-above-cents:9223372036854775807}")
    private long failAboveCents;

    @KafkaListener(topics = "${app.kafka.topic.payment-requests}",
            groupId = "payment-mock")
    public void onPaymentRequest(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        Object event = record.value();
        if (event instanceof PaymentRequestedEvent requested) {
            boolean shouldFail = requested.amountCents() > failAboveCents;
            if (shouldFail) {
                PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                        UUID.randomUUID(), Instant.now(),
                        requested.orderId(), "SIMULATED_DECLINE");
                kafkaTemplate.send(paymentTopic, String.valueOf(requested.orderId()), failedEvent);
                log.info("PaymentMock: published PaymentFailedEvent orderId={} (amount={} > threshold {})",
                        requested.orderId(), requested.amountCents(), failAboveCents);
            } else {
                PaymentChargedEvent chargedEvent = new PaymentChargedEvent(
                        UUID.randomUUID(), Instant.now(),
                        requested.orderId(), requested.amountCents(), requested.currency());
                kafkaTemplate.send(paymentTopic, String.valueOf(requested.orderId()), chargedEvent);
                log.info("PaymentMock: published PaymentChargedEvent orderId={} amount={} {}",
                        requested.orderId(), requested.amountCents(), requested.currency());
            }
        }
        ack.acknowledge();
    }
}
