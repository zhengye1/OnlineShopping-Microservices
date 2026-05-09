package com.onlineshopping.user.service;

import com.onlineshopping.user.dto.AuthResponse;
import com.onlineshopping.user.dto.LoginRequest;
import com.onlineshopping.user.dto.RegisterRequest;
import com.onlineshopping.user.entity.Role;
import com.onlineshopping.user.entity.User;
import com.onlineshopping.user.event.UserCreatedEvent;
import com.onlineshopping.user.repository.UserRepository;
import com.onlineshopping.user.security.JwtProperties;
import com.onlineshopping.user.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Auth business logic — register + login.
 *
 * <p>Separated from {@code AuthController} so unit tests can mock
 * {@code UserRepository} + {@code PasswordEncoder} + {@code JwtService}
 * without spinning up the web layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProps;
    private final OutboxService outboxService;

    /**
     * Register a new user. Returns JWT immediately (auto-login).
     *
     * @throws ResponseStatusException 409 if email already taken
     */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepo.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        User user = User.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(Role.USER)
                .build();
        user = userRepo.save(user);
        log.info("Registered user id={} email={}", user.getId(), user.getEmail());

        // Outbox: write event in the SAME transaction → atomic with user INSERT.
        // OutboxService uses Propagation.MANDATORY so this would throw if no @Transactional.
        outboxService.record(
                UserCreatedEvent.TYPE,
                user.getId().toString(),
                new UserCreatedEvent(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt())
        );

        return buildAuthResponse(user);
    }

    /**
     * Verify credentials + issue JWT.
     *
     * <p>Both "user not found" and "wrong password" return identical
     * 401 "Invalid credentials" — prevents user enumeration attack
     * where attacker probes "does this email exist" via response diff.
     *
     * @throws ResponseStatusException 401 on any auth failure
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepo.findByEmail(req.email())
                .orElseThrow(() -> {
                    log.debug("Login failed (no such user): email={}", req.email());
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
                });

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            log.debug("Login failed (wrong password): userId={}", user.getId());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.issueToken(user);
        long expiresInSeconds = jwtProps.getExpirationMinutes() * 60L;
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), expiresInSeconds);
    }
}
