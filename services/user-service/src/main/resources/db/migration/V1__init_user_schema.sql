-- V1: Initial user schema for user-service.
--
-- Note table name `users` (plural) — `user` is a reserved keyword in MySQL/PostgreSQL.
-- Schema decisions documented in lesson-04 notes.

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,                       -- L4: IDENTITY; L5+ refactor to Snowflake
    email         VARCHAR(320) NOT NULL,                                       -- RFC 5321 max email length
    password_hash VARCHAR(255) NOT NULL,                                       -- BCrypt(60) + algo-prefix room for upgrades
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER',                        -- enum stored as STRING (avoid ORDINAL trap)
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                               ON UPDATE CURRENT_TIMESTAMP(6),
    version       BIGINT       NOT NULL DEFAULT 0,                             -- @Version for JPA optimistic locking

    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;
