package com.onlineshopping.product.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Stateless JWT auth filter — every request goes through this once.
 *
 * <p>Behavior:
 * <ul>
 *   <li>No {@code Authorization} header / not Bearer → pass through unauthenticated
 *       (anonymous; SecurityFilterChain decides if endpoint is public).
 *   <li>Valid Bearer token → set {@link SecurityContextHolder} with
 *       {@code (userId, role)} so downstream code can {@code @PreAuthorize}.
 *   <li>Invalid / expired token → log debug + leave context unauthenticated
 *       (fail-loud at endpoint level rather than throwing here).
 * </ul>
 *
 * <p><b>This filter is per-microservice.</b> When we extract cart-service /
 * order-service / etc., each gets its own copy. Token issuance lives only in
 * user-service ({@link JwtService}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        try {
            Claims claims = jwtService.parseClaims(token);
            String userId = claims.getSubject();
            String role = claims.get(JwtService.CLAIM_ROLE, String.class);

            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (JwtException e) {
            // Don't 401 here — let the filter chain decide based on endpoint security.
            log.debug("Invalid JWT, leaving SecurityContext unauthenticated: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }
}
