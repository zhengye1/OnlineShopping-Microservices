package com.onlineshopping.cart.dto;

import com.onlineshopping.cart.entity.CartItem;

import java.time.Instant;

public record CartItemResponse(
        Long userId,
        Long productId,
        Integer quantity,
        Long priceAtAddition,    // cents
        String currency,         // ISO 4217
        Instant createdAt,
        Instant updatedAt
) {
    public static CartItemResponse from(CartItem item) {
        return new CartItemResponse(
                item.getUserId(),
                item.getProductId(),
                item.getQuantity(),
                item.getPriceAtAddition(),
                item.getCurrency(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
