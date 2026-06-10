-- L9 — add reservation tracking + reserved_stock counter.
--
-- Schema design notes:
--   - 'reserved_stock' counter on inventories: avoids SUM(reservations) on
--     every check. Updated atomically alongside reservation INSERT.
--   - inventory_reservation: 1 row per reservation attempt. Indexed for:
--     (a) TTL reaper scanning expires_at + ACTIVE state
--     (b) saga compensation lookup by order_id
--     (c) per-user query (UI feature later)
--   - 'release_reason' VARCHAR(64): forensics for why a reservation ended
--     up RELEASED (saga compensation / TTL expiry / user cancel).

ALTER TABLE inventories
    ADD COLUMN reserved_stock INT NOT NULL DEFAULT 0
    AFTER stock_quantity;

ALTER TABLE inventories
    ADD CONSTRAINT chk_reserved_non_negative CHECK (reserved_stock >= 0);

ALTER TABLE inventories
    ADD CONSTRAINT chk_reserved_le_stock CHECK (reserved_stock <= stock_quantity);

CREATE TABLE inventory_reservation
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id      BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    quantity        INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    release_reason  VARCHAR(64)  NULL,
    expires_at      TIMESTAMP(6) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_reservation_qty_positive CHECK (quantity > 0),
    CONSTRAINT fk_reservation_inventory FOREIGN KEY (product_id) REFERENCES inventories(product_id),
    KEY idx_reservation_expires_status (status, expires_at),
    KEY idx_reservation_order_id (order_id),
    KEY idx_reservation_user_product (user_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
