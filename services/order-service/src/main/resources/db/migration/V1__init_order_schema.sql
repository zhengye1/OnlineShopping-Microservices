-- L9 Order Service schema (V1).
--
-- Schema design notes:
--   - PK 'id' is BIGINT — populated by Snowflake (same as product / inventory)
--     so we get globally-unique, sortable, externally-visible order IDs.
--   - 'status' is VARCHAR(32) instead of MySQL ENUM — JPA EnumType.STRING
--     handles enum value evolution gracefully (adding a state doesn't need a
--     DDL change to ENUM).
--   - 'idempotency_key' has UNIQUE constraint at the DB level. Don't trust
--     the app layer alone to dedupe; the DB is the source of truth for
--     "this order was already created."
--   - 'cancel_reason' is nullable VARCHAR(64) — forensics field. Production
--     reality: SREs query this column constantly during incidents.
--   - 'version' for optimistic locking — saga compensation races against
--     TTL reaper / user retry; @Version protects us.

CREATE TABLE orders (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    status              VARCHAR(32) NOT NULL,
    total_amount_cents  BIGINT NOT NULL,
    currency            CHAR(3) NOT NULL,
    cancel_reason       VARCHAR(64) NULL,
    idempotency_key     VARCHAR(128) NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,

    UNIQUE KEY uk_orders_idempotency_key (idempotency_key),
    KEY idx_orders_user_status (user_id, status),
    KEY idx_orders_status_created (status, created_at)
);

CREATE TABLE order_items (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id              BIGINT NOT NULL,
    product_id            BIGINT NOT NULL,
    quantity              INT NOT NULL,
    price_at_order_cents  BIGINT NOT NULL,
    currency              CHAR(3) NOT NULL,

    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders(id)
        ON DELETE CASCADE,

    KEY idx_order_items_order_id (order_id),

    CONSTRAINT chk_order_items_quantity_positive CHECK (quantity > 0)
);
