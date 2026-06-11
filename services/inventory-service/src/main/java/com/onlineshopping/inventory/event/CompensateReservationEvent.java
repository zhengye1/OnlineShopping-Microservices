package com.onlineshopping.inventory.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Inventory-side mirror of order-service's CompensateReservationEvent.
 * Service-local DTO with {@code @JsonIgnoreProperties} for forward-compatible
 * schema evolution.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompensateReservationEvent(
        UUID eventId,
        Instant occurredAt,
        Long orderId,
        String reason
) {}
