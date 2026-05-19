package com.onlineshopping.inventory.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Consumer-side mirror of product-service's ProductCreatedEvent.
 *
 * <p>Deliberately separate from the producer's class (cross-service
 * Maven dependency = Distributed Monolith — L5 mistake). The contract
 * is the JSON shape on the wire, not a shared Java type.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} — additive
 * field changes (L5 hw Q2a) deserialize cleanly without consumer change.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductCreatedEvent(
        String eventId,
        String eventType,
        Integer eventVersion,
        Instant occurredAt,
        Long productId,
        String name,
        String sku,
        Long priceCents,
        String currency,
        Long categoryId,
        String status
) {
}