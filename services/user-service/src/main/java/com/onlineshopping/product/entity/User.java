package com.onlineshopping.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * User entity — maps to the {@code users} table (V1 migration).
 *
 * <p>Schema decisions (full rationale in lesson-04 doc):
 * <ul>
 *   <li>Table name {@code users} (plural) — {@code user} is reserved in MySQL/PostgreSQL.
 *   <li>{@code id} uses {@link GenerationType#IDENTITY} for L4 simplicity.
 *       L5+ refactor to Snowflake for cross-service ID generation.
 *   <li>{@code role} stored as STRING (NOT ORDINAL) — see {@link Enumerated}.
 *       Global rule: enum field MUST have explicit {@code EnumType.STRING}.
 *   <li>{@code version} for JPA optimistic locking ({@link Version}).
 *   <li>Timestamps use {@link Instant} (UTC, timezone-agnostic).
 * </ul>
 */
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_email", columnNames = "email")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private Role role = Role.USER;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;
}
