package com.onlineshopping.inventory.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * L9 inventory reservation — represents a temporary hold on stock for a
 * specific order.
 *
 * <p>Lifecycle: created ACTIVE when order saga reserves stock; transitions
 * to COMMITTED when payment succeeds (stock_quantity actually decremented)
 * or RELEASED when payment fails or TTL expires (stock freed for others).
 *
 * <p>{@code @Version} guards against compensation/TTL races. Two saga
 * handlers can race to transition the same ACTIVE reservation: the
 * payment-success handler wants COMMITTED, the TTL reaper wants RELEASED.
 * Optimistic lock ensures exactly one wins.
 */
@Entity
@Table(name = "inventory_reservation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "release_reason", length = 64)
    private String releaseReason;             // populated only on RELEASED

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
