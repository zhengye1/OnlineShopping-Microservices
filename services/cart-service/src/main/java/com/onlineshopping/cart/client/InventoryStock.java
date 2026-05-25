package com.onlineshopping.cart.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Cart-side mirror of inventory-service's GET /inventory/{id} response.
 * Same backward-compat strategy via {@code @JsonIgnoreProperties}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryStock(
        Long productId,
        Integer stockQuantity
) {
}
