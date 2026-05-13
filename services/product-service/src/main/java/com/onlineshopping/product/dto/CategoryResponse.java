package com.onlineshopping.product.dto;

import com.onlineshopping.product.entity.Category;

import java.time.Instant;

public record CategoryResponse(
        Long id,
        String name,
        String slug,
        Integer sortOrder,
        Instant createdAt
) {
    public static CategoryResponse from(Category c){
        return new CategoryResponse(
                c.getId(), c.getName(), c.getSlug(),
                c.getSortOrder(), c.getCreatedAt()
        );
    }
}
