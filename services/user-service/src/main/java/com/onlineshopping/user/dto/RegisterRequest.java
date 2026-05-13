package com.onlineshopping.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration request body for {@code POST /auth/register}.
 *
 * <p>Validation enforced via Spring {@code @Valid} at controller boundary.
 */
public record RegisterRequest(
        @Email
        @NotBlank
        @Size(max = 320)             // RFC 5321 max email length
        String email,

        @NotBlank
        @Size(min = 8, max = 100)
        String password
) {
}
