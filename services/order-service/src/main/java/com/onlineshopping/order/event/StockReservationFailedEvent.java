package com.onlineshopping.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onlineshopping.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed by order-service when inventory cannot satisfy the order. No
 * compensation needed — nothing was reserved. Order transitions
 * PENDING_INVENTORY → CANCELLED with cancelReason='STOCK_UNAVAILABLE'.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StockReservationFailedEvent(
        UUID eventId,
        Instant occurredAt,
        Long orderId,
        String reason
) implements DomainEvent {
    @Override
    public String eventType() { return "StockReservationFailed"; }
}
