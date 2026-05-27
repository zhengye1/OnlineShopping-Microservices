package com.onlineshopping.cart.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/**
 * L7: Single cart line item — one per (user, product) pair.
 *
 * <p>Design B (DynamoDB-friendly): no Cart aggregate entity, no separate Snowflake
 * ID. Composite PK {@code (user_id, product_id)} aligns with DynamoDB
 * {@code PK=user_id, SK=product_id} for future migration.
 *
 * <p>{@code priceAtAddition} + {@code currency} are SNAPSHOTS — protect user
 * from product price drift between add-to-cart and checkout. Industry standard
 * (Amazon, Shopify, Stripe).
 */
@Entity
@Table(name = "cart_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(CartItemId.class)
public class CartItem {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "price_at_addition", nullable = false)
    private Long priceAtAddition;                  // cents

    @Column(nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;                       // ISO 4217

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CartItem c)) return false;
        return userId != null && userId.equals(c.userId)
                && productId != null && productId.equals(c.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, productId);
    }
}
