package com.onlineshopping.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onlineshopping.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed by order-service (and inventory-service) after the payment mock
 * has successfully charged the customer. Triggers
 * PENDING_PAYMENT → CONFIRMED on order side and ACTIVE → COMMITTED on
 * reservation side (inventory consumes too, in Phase 6).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentChargedEvent(
        UUID eventId,
        Instant occurredAt,
        Long orderId,
        Long amountCents,
        String currency
) implements DomainEvent {
    @Override
    public String eventType() { return "PaymentCharged"; }
}
