package com.onlineshopping.inventory.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StockReservedEvent(
        UUID eventId,
        Instant occurredAt,
        Long orderId,
        Long userId,
        Long totalAmountCents,
        String currency
) {}
