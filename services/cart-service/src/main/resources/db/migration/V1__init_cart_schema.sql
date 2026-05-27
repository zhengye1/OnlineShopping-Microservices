CREATE TABLE cart_items (
    user_id            BIGINT       NOT NULL,
    product_id         BIGINT       NOT NULL,
    quantity           INT          NOT NULL,
    price_at_addition  BIGINT       NOT NULL,    -- 銀錢用 cents (long) 避免 BigDecimal serialization overhead
    currency           CHAR(3)      NOT NULL,    -- ISO 4217 (USD/CAD/etc.) snapshot at add time
    created_at         TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version            BIGINT       NOT NULL DEFAULT 0,    -- @Version optimistic lock

    PRIMARY KEY (user_id, product_id),                     -- DynamoDB-friendly composite: PK=user_id, SK=product_id
    CONSTRAINT chk_quantity_positive CHECK (quantity > 0)
);
