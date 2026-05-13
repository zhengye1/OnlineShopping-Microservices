package com.onlineshopping.product.service;

import com.onlineshopping.product.entity.OutboxEvent;
import com.onlineshopping.product.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Background poller for the transactional outbox.
 *
 * <p>Every {@code app.outbox.poll-interval-ms} (default 1 sec), scans for
 * pending events (FIFO by id) and "publishes" them.
 *
 * <p><b>L4 implementation</b>: events are logged to console — proves the
 * pattern works end-to-end. L7+ replaces the log call with a real Kafka /
 * SNS producer; downstream services (cart-service, notification-service)
 * subscribe and de-dupe by event id (consumer-side idempotency).
 *
 * <p>Concurrency: the entire poll runs in a single {@code @Transactional}
 * scope. If multiple pollers run (e.g. horizontal scale of user-service),
 * a refinement is to switch the read to {@code SELECT ... FOR UPDATE
 * SKIP LOCKED} — beyond L4 scope.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {

    private final OutboxEventRepository outboxRepo;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxRepo.findTop100ByPublishedAtIsNullOrderByIdAsc();
        if (pending.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        for (OutboxEvent event : pending) {
            // L4: log only. L7+ replaces with kafkaTemplate.send(topic, event.id, event.payload).
            log.info("[outbox] PUBLISH id={} type={} aggregate={} payload={}",
                    event.getId(), event.getEventType(), event.getAggregateId(), event.getPayload());
            event.setPublishedAt(now);
        }
        outboxRepo.saveAll(pending);
        log.debug("[outbox] published {} events", pending.size());
    }
}
