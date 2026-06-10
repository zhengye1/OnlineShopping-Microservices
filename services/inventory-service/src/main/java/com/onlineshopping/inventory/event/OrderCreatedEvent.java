package com.onlineshopping.inventory.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inventory-side mirror of order-service's OrderCreatedEvent. Service-local
 * DTO with {@code @JsonIgnoreProperties} for forward-compatible schema
 * evolution.
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
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(Long productId, Integer quantity, Long priceAtOrderCents) {}
}
