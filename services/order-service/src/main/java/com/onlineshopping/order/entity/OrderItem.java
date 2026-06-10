package com.onlineshopping.order.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Order line item.
 *
 * <p>Each item snapshots {@code priceAtOrderCents} at order-creation time
 * — same pattern as cart's {@code priceAtAddition}. Once an order is
 * created, price changes in product-service do NOT propagate; the order
 * is a contractual snapshot.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "price_at_order_cents", nullable = false)
    private Long priceAtOrderCents;

    @Column(nullable = false, length = 3)
    private String currency;
}
