-- V3: Backfill audit columns to categories + product_images.
--
-- Context: V1 schema 遺漏咗呢兩個 table 嘅 standard audit columns
-- (created_at, updated_at, deleted_at, version)。JPA schema validation
-- 喺 L5.4 entity 落地時抓到 mismatch。
--
-- 點解唔修 V1：applied migrations are immutable — 修 V1 會令 Flyway
-- checksum validation 喺所有現有 env 拒絕 boot。Forward-only fix
-- via V3 係 production-correct discipline。

ALTER TABLE categories
    ADD COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
           ADD COLUMN deleted_at TIMESTAMP(6) NULL DEFAULT NULL,
           ADD COLUMN version    BIGINT       NOT NULL DEFAULT 0;

ALTER TABLE product_images
    ADD COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
           ADD COLUMN deleted_at TIMESTAMP(6) NULL DEFAULT NULL,
           ADD COLUMN version    BIGINT       NOT NULL DEFAULT 0;