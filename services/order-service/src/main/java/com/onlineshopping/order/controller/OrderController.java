package com.onlineshopping.order.controller;

import com.onlineshopping.order.dto.CreateOrderRequest;
import com.onlineshopping.order.dto.OrderResponse;
import com.onlineshopping.order.entity.Order;
import com.onlineshopping.order.repository.OrderRepository;
import com.onlineshopping.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepo;

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateOrderRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        log.info("POST /orders userId={} items={} idempotencyKey={}",
                userId, req.items().size(), idempotencyKey);
        Order order = orderService.create(userId, req, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long userId = Long.valueOf(jwt.getSubject());
        Order order = orderRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + id));
        if (!order.getUserId().equals(userId)) {
            // 404 not 403 — don't leak existence of other users' orders.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + id);
        }
        return OrderResponse.from(order);
    }
}
