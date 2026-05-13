package com.onlineshopping.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(@NotBlank @Size(max = 128) String name,
                                    @NotBlank @Pattern(regexp = "^[a-z0-9-]{1,128}$") String slug,
                                    Long parentId,
                                    @PositiveOrZero Integer sortOrder) {
}
