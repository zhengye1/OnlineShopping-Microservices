package com.onlineshopping.product.entity;

/**
 * User RBAC role.
 *
 * <p>L3 design decision: simple enum (3 fixed roles) instead of normalized
 * {@code role} + {@code user_role} join table. If RBAC complexity grows
 * (custom roles / permission grants), refactor later.
 */
public enum Role {
    USER,
    ADMIN,
    SELLER
}
