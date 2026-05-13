package com.onlineshopping.product.service;

import com.onlineshopping.product.dto.AuthResponse;
import com.onlineshopping.product.dto.LoginRequest;
import com.onlineshopping.product.dto.RegisterRequest;
import com.onlineshopping.product.entity.Role;
import com.onlineshopping.product.entity.User;
import com.onlineshopping.product.repository.UserRepository;
import com.onlineshopping.product.security.JwtProperties;
import com.onlineshopping.product.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepo;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock JwtProperties jwtProps;
    @Mock OutboxService outboxService;
    @InjectMocks AuthService authService;

    // ✅ WORKED EXAMPLE — copy pattern for the rest
    @Test
    void register_newEmail_succeeds() {
        // Arrange
        RegisterRequest req = new RegisterRequest("alice@example.com", "hunter2!23");
        when(userRepo.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("hunter2!23")).thenReturn("$2a$12$encoded");
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);  // simulate IDENTITY-generated PK
            return u;
        });
        when(jwtService.issueToken(any(User.class))).thenReturn("fake.jwt.token");
        when(jwtProps.getExpirationMinutes()).thenReturn(60L);

        // Act
        AuthResponse resp = authService.register(req);

        // Assert
        assertThat(resp.token()).isEqualTo("fake.jwt.token");
        assertThat(resp.userId()).isEqualTo(42L);
        assertThat(resp.email()).isEqualTo("alice@example.com");
        assertThat(resp.role()).isEqualTo(Role.USER);
        assertThat(resp.expiresInSeconds()).isEqualTo(3600L);
        verify(outboxService).record(eq("UserCreated"), eq("42"), any());
    }

    @Test
    void register_existingEmail_throws409() {
        // Arrange
        RegisterRequest req = new RegisterRequest("alice@example.com", "hunter2!23");
        when(userRepo.existsByEmail("alice@example.com")).thenReturn(true);

        // Act
        ResponseStatusException ex = catchThrowableOfType(
                ResponseStatusException.class,
                () -> authService.register(req));
        // Assert
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).isEqualTo("Email already registered");
    }

    @Test
    void login_validCredentials_returnsJwt() {
        // Arrange
        LoginRequest req = new LoginRequest("alice@example.com", "hunter2!23");
        User user = User.builder()
                .id(42L)
                .email("alice@example.com")
                .passwordHash("$2a$12$encoded")
                .role(Role.USER)
                .build();
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("hunter2!23", "$2a$12$encoded")).thenReturn(true);
        when(jwtService.issueToken(user)).thenReturn("fake.jwt.token");
        when(jwtProps.getExpirationMinutes()).thenReturn(60L);

        // Act
        AuthResponse resp = authService.login(req);
        // Assert
        assertThat(resp.token()).isEqualTo("fake.jwt.token");
        assertThat(resp.userId()).isEqualTo(42L);
        assertThat(resp.email()).isEqualTo("alice@example.com");
        assertThat(resp.role()).isEqualTo(Role.USER);
        assertThat(resp.expiresInSeconds()).isEqualTo(3600L);
    }

    @Test
    void login_unknownEmail_throws401() {
        // Arrange
        LoginRequest req = new LoginRequest("alice@example.com", "wrongpw");
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.empty());

        // Act
        ResponseStatusException ex = catchThrowableOfType(
                ResponseStatusException.class,
                () -> authService.login(req));
        // Assert
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo("Invalid credentials");
    }

    @Test
    void login_wrongPassword_throws401() {
        LoginRequest req = new LoginRequest("alice@example.com", "wrongpw");
        User user = User.builder()
                .id(42L)
                .email("alice@example.com")
                .passwordHash("$2a$12$encoded")
                .role(Role.USER)
                .build();
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpw", "$2a$12$encoded")).thenReturn(false);
        // Act
        ResponseStatusException ex = catchThrowableOfType(
                ResponseStatusException.class,
                () -> authService.login(req));

        // Assert — IDENTICAL message to login_unknownEmail (no enumeration leak)
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo("Invalid credentials");

    }
}