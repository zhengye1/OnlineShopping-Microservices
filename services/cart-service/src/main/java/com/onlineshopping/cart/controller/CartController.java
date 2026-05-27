package com.onlineshopping.cart.controller;

import com.onlineshopping.cart.dto.AddCartItemRequest;
import com.onlineshopping.cart.dto.CartItemResponse;
import com.onlineshopping.cart.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * L7 Phase 3: cart HTTP boundary. User identity extracted from JWT via
 * {@link AuthenticationPrincipal} — cleaner DI than SecurityContextHolder
 * and naturally testable.
 *
 * <p>JWT {@code sub} claim is the userId (set by user-service at token issue
 * via {@code user.getId().toString()}). Parse back to Long here.
 */
@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @PostMapping("/items")
    public ResponseEntity<CartItemResponse> addItem(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddCartItemRequest req) {
        Long userId = Long.valueOf(jwt.getSubject());
        CartItemResponse response = cartService.addItem(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
