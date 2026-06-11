package com.onlineshopping.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onlineshopping.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by order-service when a saga step fails AFTER stock was already
 * reserved — specifically, the payment-failed branch. Inventory-service
 * consumes this and releases any ACTIVE reservations tied to the order.
 *
 * <p>{@code reason} propagates downstream so the inventory side can populate
 * {@code release_reason} for forensics ("why was this reservation released?").
 *
 * <p>Compensation is idempotent by design — repeated delivery is safe because
 * {@link com.onlineshopping.order.service.OrderService} only publishes ONCE
 * during PENDING_PAYMENT → CANCELLED transition, and inventory's release
 * function is no-op when reservations are already RELEASED.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompensateReservationEvent(
        UUID eventId,
        Instant occurredAt,
        Long orderId,
        String reason
) implements DomainEvent {
    @Override
    public String eventType() { return "CompensateReservation"; }
}
