package com.onlineshopping.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onlineshopping.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by order-service immediately after transitioning to
 * PENDING_PAYMENT. Payment service (mock or real) consumes this and
 * publishes the payment outcome.
 *
 * <p><b>Why a separate event from StockReservedEvent:</b> If the payment
 * service listens to StockReservedEvent directly, it races with
 * order-service's own consumer of the same event. The payment outcome can
 * land back at order-service BEFORE order's state has transitioned to
 * PENDING_PAYMENT, and handlePaymentCharged's state-guard then ignores
 * the event — the order is stuck.
 *
 * <p>Serializing the saga via PaymentRequestedEvent makes the ordering
 * explicit: payment is only kicked off AFTER the state machine has moved
 * to PENDING_PAYMENT, so the eventual PaymentChargedEvent / PaymentFailedEvent
 * cannot race with the upstream transition.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRequestedEvent(
        UUID eventId,
        Instant occurredAt,
        Long orderId,
        Long amountCents,
        String currency
) implements DomainEvent {
    @Override
    public String eventType() { return "PaymentRequested"; }
}
