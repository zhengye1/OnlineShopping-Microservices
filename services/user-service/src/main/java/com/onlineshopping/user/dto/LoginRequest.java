package com.onlineshopping.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Login request body for {@code POST /auth/login}.
 *
 * <p>Note: no {@code @Size} on password — login should not impose
 * length policy that may differ from registration time (account may
 * have been created with an older policy).
 */
public record LoginRequest(
        @Email
        @NotBlank
        String email,

        @NotBlank
        String password
) {
}
