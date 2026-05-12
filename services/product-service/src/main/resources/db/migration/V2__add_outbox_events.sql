-- V2: Outbox events table for transactional outbox pattern.
--
-- Pattern: business writes (e.g. AuthService.register) commit a row in this
-- table in the SAME @Transactional as the entity write. A background poller
-- (OutboxPoller) periodically scans for unpublished events and dispatches
-- them downstream (logged to console in L4; real Kafka/SNS publishing in L7+).
--
-- Atomic guarantee: because entity + outbox row share one DB transaction,
-- either both commit or both rollback — no message lost, no message
-- without underlying state change.
--
-- Index on (published_at, id) optimizes the poller's hot path:
--   SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY id ASC LIMIT N;

CREATE TABLE outbox_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    event_type    VARCHAR(64)  NOT NULL,                                     -- e.g. 'UserCreated', 'UserUpdated'
    aggregate_id  VARCHAR(64)  NOT NULL,                                     -- e.g. user.id (kept as string for flexibility)
    payload       TEXT         NOT NULL,                                     -- JSON-serialized event body
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at  TIMESTAMP(6) NULL DEFAULT NULL,                            -- NULL = pending; non-NULL = published

    PRIMARY KEY (id),
    KEY idx_outbox_pending (published_at, id)                                -- composite: hits pending first, then FIFO order
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;
