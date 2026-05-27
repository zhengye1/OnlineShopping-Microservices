package com.onlineshopping.user.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * JWT signing + verification config bound from {@code app.jwt.*} in application.yml.
 *
 * <p>L7 RS256 migration: private key signs locally (NEVER leaves user-service),
 * public key shipped via {@code /.well-known/jwks.json} for cross-service local verify.
 * {@code keyId} matches the JWT {@code kid} header so verifiers know which public
 * key to use (enables zero-downtime key rotation).
 *
 * <p>Production: load PEM files from K8s Secret / AWS Secrets Manager,
 * NEVER commit private key to repo (see {@code .gitignore}).
 */
@ConfigurationProperties(prefix = "app.jwt")
@Validated
@Getter
@Setter
public class JwtProperties {

    @NotNull
    private Resource privateKey;

    @NotNull
    private Resource publicKey;

    @NotBlank
    private String keyId;

    @Positive
    private long expirationMinutes;

    @NotBlank
    private String issuer;
}
