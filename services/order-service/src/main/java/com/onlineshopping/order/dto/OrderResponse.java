package com.onlineshopping.order.dto;

import com.onlineshopping.order.entity.Order;
import com.onlineshopping.order.entity.OrderStatus;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        Long totalAmountCents,
        String currency,
        String cancelReason,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt
) {
    public record Item(Long productId, Integer quantity, Long priceAtOrderCents, String currency) {}

    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.getId(),
                o.getUserId(),
                o.getStatus(),
                o.getTotalAmountCents(),
                o.getCurrency(),
                o.getCancelReason(),
                o.getItems().stream()
                        .map(i -> new Item(i.getProductId(), i.getQuantity(),
                                i.getPriceAtOrderCents(), i.getCurrency()))
                        .toList(),
                o.getCreatedAt(),
                o.getUpdatedAt()
        );
    }
}
