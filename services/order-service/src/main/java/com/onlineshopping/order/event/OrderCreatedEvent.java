package com.onlineshopping.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onlineshopping.events.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by order-service after a successful POST /orders. Inventory-service
 * consumes this to reserve stock for the order; the saga's first step.
 *
 * <p>Service-local DTO (vs sharing the entity) — same {@code @JsonIgnoreProperties}
 * pattern as L6 ProductCreatedEvent. Forward-compatible schema evolution
 * without coupling order-service entity to inventory-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreatedEvent(
        UUID eventId,
        Instant occurredAt,
        Long orderId,
        Long userId,
        List<Item> items,
        Long totalAmountCents,
        String currency
) implements DomainEvent {

    @Override
    public String eventType() { return "OrderCreated"; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(Long productId, Integer quantity, Long priceAtOrderCents) {}
}
