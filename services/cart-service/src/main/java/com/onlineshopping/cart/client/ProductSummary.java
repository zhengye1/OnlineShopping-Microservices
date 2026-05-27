package com.onlineshopping.cart.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Cart-side mirror of product-service's product response — only the fields
 * cart-service actually needs (id / priceCents / currency for snapshot).
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} lets product-service
 * add new fields without breaking cart-service deserialization. Same
 * backward-compat strategy as inventory-service's ProductCreatedEvent mirror.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSummary(
        Long id,
        String name,
        String sku,
        Long priceCents,
        String currency,
        String status
) {
}
