package com.onlineshopping.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Outbox event row — backs the transactional outbox pattern (L3).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Business code calls {@code OutboxService.record()} from inside a
 *       {@code @Transactional} method → INSERT row with {@code publishedAt=null}.
 *   <li>Background {@code OutboxPoller} fetches pending rows (FIFO),
 *       dispatches downstream, then sets {@code publishedAt=now()}.
 * </ol>
 *
 * <p>The poller can rerun on the same row idempotently as long as the
 * downstream consumer is also idempotent (de-dupe by {@code id}).
 */
@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_pending", columnList = "published_at, id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;
}
