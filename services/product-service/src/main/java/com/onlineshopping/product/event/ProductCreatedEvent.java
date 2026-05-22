package com.onlineshopping.product.event;

import com.onlineshopping.product.entity.Product;

import java.time.Instant;
import java.util.UUID;

public record ProductCreatedEvent(String eventId,           // UUID — consumer dedup key
                                  String eventType,         // "ProductCreated" — for routing
                                  int eventVersion,         // 1
                                  Instant occurredAt,       // domain time

                                  // Aggregate
                                  Long productId,
                                  String name,
                                  String sku,
                                  Long priceCents,
                                  String currency,
                                  Long categoryId,
                                  String status,
                                  Integer initialStock) {   // L7: propagate stock to inventory-service
    public static ProductCreatedEvent from(Product p) {
        return new ProductCreatedEvent(
                UUID.randomUUID().toString(),
                "ProductCreated",
                1,
                Instant.now(),
                p.getId(),
                p.getName(),
                p.getSku(),
                p.getPriceCents(),
                p.getCurrency(),
                p.getCategoryId(),
                p.getStatus().name(),
                p.getStockQuantity()
        );
    }
}
