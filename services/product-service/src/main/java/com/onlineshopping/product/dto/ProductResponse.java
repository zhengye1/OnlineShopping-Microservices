package com.onlineshopping.product.dto;

import com.onlineshopping.product.entity.Product;

import java.time.Instant;

public record ProductResponse(
        Long id,
        String name,
        String description,
        String sku,
        Long priceCents,
        String currency,
        Long categoryId,
        String status,
        Integer stockQuantity,
        Instant createdAt
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(), p.getName(), p.getDescription(),
                p.getSku(), p.getPriceCents(), p.getCurrency(),
                p.getCategoryId(), p.getStatus().name(),
                p.getStockQuantity(), p.getCreatedAt()
        );
    }
}
