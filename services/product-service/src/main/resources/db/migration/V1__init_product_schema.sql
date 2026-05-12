CREATE TABLE categories
(
    id         BIGINT       NOT NULL,
    name       VARCHAR(128) NOT NULL,
    slug       VARCHAR(128) NOT NULL,           -- URL-friendly: "electronics-phones"
    parent_id  BIGINT       NULL,               -- NULL = root category
    sort_order INT          NOT NULL DEFAULT 0, -- display ordering among siblings
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_slug (slug),
    KEY        idx_categories_parent(parent_id),
    CONSTRAINT fk_categories_parent
        FOREIGN KEY (parent_id) REFERENCES categories (id)
            ON DELETE RESTRICT                  -- 防止意外刪 parent
);

CREATE TABLE products
(
    id             BIGINT       NOT NULL,                          -- Snowflake, app-generated
    name           VARCHAR(255) NOT NULL,
    description    TEXT         NULL,
    sku            VARCHAR(64)  NOT NULL,                          -- Stock Keeping Unit, unique
    price_cents    BIGINT       NOT NULL,                          -- 銀錢用 BIGINT cents，避免 float
    currency       CHAR(3)      NOT NULL DEFAULT 'CAD',            -- ISO 4217
    category_id    BIGINT       NOT NULL,
    status         VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',          -- enum stored as STRING
    stock_quantity INT          NOT NULL DEFAULT 0,                -- L5 keep here; L8 inventory-service 抽走

    created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at     TIMESTAMP(6) NULL     DEFAULT NULL,             -- soft delete
    version        BIGINT       NOT NULL DEFAULT 0,                -- @Version optimistic lock

    PRIMARY KEY (id),
    UNIQUE KEY uk_products_sku (sku),
    KEY            idx_products_category(category_id, deleted_at), -- filter "active products in category"
    KEY            idx_products_status(status, deleted_at),
    CONSTRAINT fk_products_category
        FOREIGN KEY (category_id) REFERENCES categories (id)
            ON DELETE RESTRICT
) ENGINE=InnoDB
    DEFAULT CHARSET=utf8mb4
    COLLATE=utf8mb4_unicode_ci;

CREATE TABLE product_images
(
    id         BIGINT        NOT NULL,
    product_id BIGINT        NOT NULL,
    url        VARCHAR(1024) NOT NULL,
    alt_text   VARCHAR(255)  NULL,
    sort_order INT           NOT NULL DEFAULT 0,
    is_primary BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY        idx_product_images_product(product_id, sort_order),
    CONSTRAINT fk_product_images_product
        FOREIGN KEY (product_id) REFERENCES products (id)
            ON DELETE CASCADE -- product 刪走，圖跟住刪
);