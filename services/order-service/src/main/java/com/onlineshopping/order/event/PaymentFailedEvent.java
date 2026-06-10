package com.onlineshopping.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onlineshopping.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed by order-service when payment cannot be charged. Triggers
 * the compensation flow: order → CANCELLED + inventory must RELEASE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentFailedEvent(
        UUID eventId,
        Instant occurredAt,
        Long orderId,
        String reason
) implements DomainEvent {
    @Override
    public String eventType() { return "PaymentFailed"; }
}
