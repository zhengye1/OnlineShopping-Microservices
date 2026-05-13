package com.onlineshopping.product.event;

import com.onlineshopping.product.entity.Role;

import java.time.Instant;

/**
 * Domain event emitted when a user is registered.
 *
 * <p>Recorded transactionally in the outbox by {@code AuthService.register()}.
 * Future consumers (cart-service, notification-service, etc.) will subscribe
 * via Kafka/SNS in L7+.
 *
 * <p>Note: this lives in the local {@code event} package for L4. When
 * cross-service consumption arrives, promote to {@code shared/common-events}
 * so consumers depend on a versioned contract — but **only after** the
 * shape stabilizes (avoid premature shared-module coupling — L2 lesson).
 */
public record UserCreatedEvent(
        Long userId,
        String email,
        Role role,
        Instant createdAt
) {
    public static final String TYPE = "UserCreated";
}
