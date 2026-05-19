-- L6 — initial inventory schema
-- inventories: 1-to-1 with products (product_id is natural PK, no separate snowflake)
-- processed_events: consumer-side idempotency dedup table

CREATE TABLE inventories
(
    product_id     BIGINT       NOT NULL,
    stock_quantity INT          NOT NULL DEFAULT 0,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (product_id),
    CONSTRAINT chk_stock_non_negative CHECK (stock_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE processed_events
(
    event_id     VARCHAR(36)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;