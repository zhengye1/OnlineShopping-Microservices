package com.onlineshopping.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(
        @NotEmpty @Valid List<Item> items,
        @NotNull @Size(min = 3, max = 3) String currency
) {
    public record Item(
            @NotNull @Positive Long productId,
            @NotNull @Positive Integer quantity,
            @NotNull @Positive Long priceAtOrderCents
    ) {}
}
