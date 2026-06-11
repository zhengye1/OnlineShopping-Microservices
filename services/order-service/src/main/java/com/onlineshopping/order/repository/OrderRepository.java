package com.onlineshopping.order.repository;

import com.onlineshopping.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Idempotency-Key lookup — short-circuit duplicate POST /orders. */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
}
