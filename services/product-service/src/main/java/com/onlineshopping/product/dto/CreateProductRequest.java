package com.onlineshopping.product.dto;

import jakarta.validation.constraints.*;

public record CreateProductRequest(@NotBlank @Size(max = 255) String name,
                                   @Size(max = 65535) String description,
                                   @NotBlank @Pattern(regexp = "^[A-Z0-9-]{3,64}$") String sku,
                                   @NotNull @PositiveOrZero Long priceCents,
                                   @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
                                   @NotNull Long categoryId,
                                   @PositiveOrZero Integer initialStock) {
}
