package com.onlineshopping.product.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "categories")
@Getter
@Setter
public class Category {
    @Id
    @Column(nullable = false)
    private Long id;                       // Snowflake

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 128, unique = true)
    private String slug;

    @Column(name = "parent_id")
    private Long parentId;                 // ← Long, NOT @ManyToOne self-reference

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    // + created_at, updated_at, deleted_at, version (mirror Product)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;                  // ← soft delete

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

    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}