package com.onlineshopping.order.entity;

/**
 * L9 saga state machine — fine-grained states map 1:1 to saga steps for
 * production-grade observability. SRE can grep
 * {@code SELECT status FROM orders WHERE created_at > NOW() - INTERVAL 1 HOUR
 * AND status NOT IN ('CONFIRMED', 'CANCELLED')} to see exactly which step
 * stalled orders are stuck on.
 *
 * <p>State transitions (happy path):
 * <pre>
 *   PENDING_INVENTORY
 *        ↓ (StockReservedEvent received)
 *   PENDING_PAYMENT
 *        ↓ (PaymentChargedEvent received)
 *   CONFIRMED  ← terminal success
 * </pre>
 *
 * <p>Failure transitions:
 * <pre>
 *   PENDING_INVENTORY ─StockReservationFailed─► CANCELLED (no compensation — nothing reserved yet)
 *   PENDING_PAYMENT   ─PaymentFailed─────────► CANCELLED (compensation: release reservation)
 * </pre>
 */
public enum OrderStatus {

    /** Initial state after POST /orders. Waiting for inventory reservation. */
    PENDING_INVENTORY,

    /** Stock reserved successfully. Waiting for payment to charge. */
    PENDING_PAYMENT,

    /** Terminal success — stock reserved + payment charged. */
    CONFIRMED,

    /**
     * Terminal failure. Either inventory was unavailable (no compensation
     * needed) or payment failed after reservation (saga emits
     * CompensateReservationEvent before reaching this state).
     */
    CANCELLED
}
