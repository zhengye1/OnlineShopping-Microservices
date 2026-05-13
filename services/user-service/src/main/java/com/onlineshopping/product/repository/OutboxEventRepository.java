package com.onlineshopping.product.repository;

import com.onlineshopping.product.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link OutboxEvent}.
 *
 * <p>Hot path: poller reads pending events (FIFO) backed by the
 * {@code idx_outbox_pending(published_at, id)} composite index from V2.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Fetch up to 100 pending events ordered by id (FIFO insertion order).
     *
     * <p>Spring Data derives:
     * {@code SELECT * FROM outbox_events WHERE published_at IS NULL
     *        ORDER BY id ASC LIMIT 100}
     */
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();
}
