package com.onlineshopping.user.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT signing + verification config bound from {@code app.jwt.*} in application.yml.
 *
 * <p>{@code secret} should be ≥256 bits (32 chars) for HS256.
 * Production deployments MUST override via {@code JWT_SECRET} env var.
 */
@ConfigurationProperties(prefix = "app.jwt")
@Validated
@Getter
@Setter
public class JwtProperties {

    @NotBlank
    private String secret;

    @Positive
    private long expirationMinutes;

    @NotBlank
    private String issuer;
}
