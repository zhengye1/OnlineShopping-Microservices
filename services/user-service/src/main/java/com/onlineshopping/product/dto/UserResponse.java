package com.onlineshopping.product.dto;

import com.onlineshopping.product.entity.Role;

import java.time.Instant;

/**
 * Response body for {@code GET /users/me}.
 *
 * <p>Intentionally excludes {@code passwordHash}, {@code version},
 * {@code updatedAt} — caller doesn't need them, and exposing
 * {@code passwordHash} is a critical security violation.
 */
public record UserResponse(
        Long id,
        String email,
        Role role,
        Instant createdAt
) {
}
