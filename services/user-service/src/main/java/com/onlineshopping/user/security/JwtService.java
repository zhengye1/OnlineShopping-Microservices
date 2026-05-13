package com.onlineshopping.user.security;

import com.onlineshopping.user.entity.Role;
import com.onlineshopping.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT token issuance + verification (HS256).
 *
 * <p>Token claim shape:
 * <ul>
 *   <li>{@code sub}  — user ID (NOT username — username/email is mutable, ID is canonical)
 *   <li>{@code iss}  — issuer (matches {@link JwtProperties#getIssuer()})
 *   <li>{@code iat}  — issued at (UTC epoch seconds)
 *   <li>{@code exp}  — expiration (UTC epoch seconds)
 *   <li>{@code role} — RBAC role string ({@link Role#name()})
 * </ul>
 *
 * <p>L4 uses HS256 (symmetric) for course MVP simplicity.
 * Production grade (L18+) — switch to RS256 (asymmetric) so each microservice
 * holds only the public key for verification; private key stays in user-service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    public static final String CLAIM_ROLE = "role";

    private final JwtProperties props;

    /** Issue a signed JWT for the given user. Called from login + register flows. */
    public String issueToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.getExpirationMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(user.getId().toString())
                .issuer(props.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(CLAIM_ROLE, user.getRole().name())
                .signWith(signingKey())
                .compact();
    }

    /**
     * Parse + verify a JWT. Throws {@link JwtException} for any failure
     * (bad signature / expired / wrong issuer / malformed).
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey())
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

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
