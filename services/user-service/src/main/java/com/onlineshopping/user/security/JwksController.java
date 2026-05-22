package com.onlineshopping.user.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RFC 7517 JWKS endpoint — exposes user-service's public key(s) so that
 * downstream services (cart, product, inventory) can verify JWTs locally
 * without ever round-tripping back to user-service.
 *
 * <p>Endpoint MUST be public (no auth) — see {@link SecurityConfig} permit list.
 *
 * <p>Production: add HTTP cache headers (Cache-Control: max-age=3600 + ETag)
 * so consumers cache the response; rotate keys without thundering herd.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {
    private final JwtService jwtService;

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        RSAKey jwk = new RSAKey.Builder(jwtService.getPublicKey())
                .keyID(jwtService.getKeyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();

        return new JWKSet(jwk).toJSONObject();
    }
}
