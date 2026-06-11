package com.onlineshopping.inventory.entity;

/**
 * Reservation lifecycle states.
 *
 * <p>Transitions:
 * <pre>
 *   ACTIVE ──saga payment success──► COMMITTED  (terminal)
 *   ACTIVE ──saga compensation────► RELEASED   (terminal, release_reason='PAYMENT_FAILED')
 *   ACTIVE ──TTL reaper───────────► RELEASED   (terminal, release_reason='TTL_EXPIRED')
 *   ACTIVE ──user cancel──────────► RELEASED   (terminal, release_reason='USER_CANCELLED')
 * </pre>
 *
 * <p>The {@code release_reason} column distinguishes WHY a reservation ended
 * up RELEASED — critical for incident review and revenue forecasting.
 */
public enum ReservationStatus {

    /** Holding stock — counted in inventories.reserved_stock. */
    ACTIVE,

    /** Order confirmed — stock_quantity decremented, reserved_stock decremented. Terminal. */
    COMMITTED,

    /** Stock freed for other users — reserved_stock decremented. Terminal. */
    RELEASED
}
