package com.onlineshopping.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onlineshopping.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed by order-service (and payment-mock) after inventory-service has
 * successfully reserved stock for an order. Triggers order state transition
 * PENDING_INVENTORY → PENDING_PAYMENT.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StockReservedEvent(
        UUID eventId,
        Instant occurredAt,
        Long orderId,
        Long userId,
        Long totalAmountCents,
        String currency
) implements DomainEvent {
    @Override
    public String eventType() { return "StockReserved"; }
}
