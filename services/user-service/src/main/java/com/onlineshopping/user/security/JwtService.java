package com.onlineshopping.user.security;

import com.onlineshopping.user.entity.Role;
import com.onlineshopping.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT token issuance + verification (RS256 asymmetric).
 *
 * <p>L7 migration: HS256 shared-secret → RS256 public/private key pair.
 * Private key signs locally (NEVER leaves user-service); public key is shipped
 * via {@code /.well-known/jwks.json} so cross-service consumers (cart / product
 * / inventory) can verify tokens with zero round-trip back to user-service.
 *
 * <p>Token header carries {@code kid} matching {@link JwtProperties#getKeyId()}
 * — enables zero-downtime key rotation: publish new {@code kid} in JWKS, sign
 * new tokens with new key, old tokens still verify via cached old key until TTL.
 *
 * <p>Token claim shape:
 * <ul>
 *   <li>{@code sub}  — user ID (NOT username — username/email is mutable, ID is canonical)
 *   <li>{@code iss}  — issuer (matches {@link JwtProperties#getIssuer()})
 *   <li>{@code iat}  — issued at (UTC epoch seconds)
 *   <li>{@code exp}  — expiration (UTC epoch seconds)
 *   <li>{@code role} — RBAC role string ({@link Role#name()})
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    public static final String CLAIM_ROLE = "role";

    private final JwtProperties props;

    private RSAPrivateKey privateKey;

    @Getter
    private RSAPublicKey publicKey;

    @PostConstruct
    public void loadKeys() {
        try (InputStream in = props.getPrivateKey().getInputStream()) {
            this.privateKey = RsaKeyConverters.pkcs8().convert(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load JWT private key from " + props.getPrivateKey(), e);
        }
        try (InputStream in = props.getPublicKey().getInputStream()) {
            this.publicKey = RsaKeyConverters.x509().convert(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load JWT public key from " + props.getPublicKey(), e);
        }
    }

    /** Issue a signed JWT for the given user. Called from login + register flows. */
    public String issueToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.getExpirationMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .header().keyId(props.getKeyId()).and()
                .subject(user.getId().toString())
                .issuer(props.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(CLAIM_ROLE, user.getRole().name())
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Parse + verify a JWT. Throws {@link JwtException} for any failure
     * (bad signature / expired / wrong issuer / malformed).
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(props.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            log.debug("JWT parse failed: {}", e.getMessage());
            throw e;
        }
    }

    /** Convenience accessor — extract user ID from a verified token. */
    public Long parseUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /** Convenience accessor — extract role from a verified token. */
    public Role parseRole(String token) {
        return Role.valueOf(parseClaims(token).get(CLAIM_ROLE, String.class));
    }

    public String getKeyId() {
        return props.getKeyId();
    }
}
