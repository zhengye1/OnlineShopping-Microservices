package com.onlineshopping.user.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 configuration for user-service.
 *
 * <p>Decisions:
 * <ul>
 *   <li>CSRF disabled — stateless API, no session cookies.
 *   <li>Session policy STATELESS — no HttpSession created.
 *   <li>{@code /auth/**} public (login + register endpoints).
 *   <li>{@code /actuator/**} public for health probes (production-grade
 *       deployments lock down sensitive endpoints separately).
 *   <li>BCrypt strength 12 — ~250ms per hash on modern CPU, balances
 *       security vs login latency. Production may go 13-14.
 * </ul>
 *
 * <p>NOTE: We do NOT register a {@code DaoAuthenticationProvider} or
 * {@code UserDetailsService} — login flow does password verification
 * directly in {@code AuthController} for clarity. Spring Security's
 * authentication infrastructure is reserved for filter-chain authorization.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/health", "/actuator/**", "/error").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
