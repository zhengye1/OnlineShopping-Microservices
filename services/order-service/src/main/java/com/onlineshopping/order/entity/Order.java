package com.onlineshopping.order.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * L9 Order aggregate root.
 *
 * <p>Owns its state machine — {@link OrderStatus} reflects which saga step
 * the order is currently at. Items are persisted as a child collection via
 * {@code @OneToMany} cascade.
 *
 * <p>{@code cancelReason} is nullable and used for forensics — incident
 * review queries like "show all orders cancelled due to payment failure"
 * become trivial.
 *
 * <p>{@code @Version} enables optimistic locking. Critical for saga
 * compensation race: the TTL reaper job and the payment-failed event handler
 * may both try to transition an order to CANCELLED simultaneously.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    private Long id;                          // assigned by SnowflakeIdGenerator at creation

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount_cents", nullable = false)
    private Long totalAmountCents;

    @Column(nullable = false, length = 3)
    private String currency;                  // ISO 4217

    @Column(name = "cancel_reason", length = 64)
    private String cancelReason;              // nullable — populated only when status=CANCELLED

    @Column(name = "idempotency_key", length = 128, unique = true)
    private String idempotencyKey;            // unique across all orders — DB enforces

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Convenience for saga handlers to flag the failure reason on cancel. */
    public void cancel(String reason) {
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = reason;
    }
}
