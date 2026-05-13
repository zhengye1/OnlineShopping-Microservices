package com.onlineshopping.product.dto;

import com.onlineshopping.product.entity.Role;

/**
 * Response body for {@code POST /auth/register} + {@code POST /auth/login}.
 *
 * <p>Returned on successful authentication. Client stores {@code token}
 * (typically in memory or sessionStorage — NOT localStorage for XSS safety)
 * and sends back as {@code Authorization: Bearer <token>} on subsequent
 * authenticated requests.
 *
 * @param token            JWT compact form ({@code <header>.<payload>.<sig>})
 * @param userId           authenticated user ID (matches {@code sub} claim)
 * @param email            user's email
 * @param role             RBAC role (matches {@code role} claim)
 * @param expiresInSeconds TTL hint so clients can preemptively refresh
 */
public record AuthResponse(
        String token,
        Long userId,
        String email,
        Role role,
        long expiresInSeconds
) {
}
