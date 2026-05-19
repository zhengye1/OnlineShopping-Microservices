package com.onlineshopping.product.service;

import com.onlineshopping.product.entity.OutboxEvent;
import com.onlineshopping.product.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Background poller — drains outbox table to Kafka topic.
 *
 * <p>L6 upgrade: replaces the L4 console-log placeholder with real
 * {@link KafkaTemplate} publish. Synchronous {@code .get()} on the send
 * future is the cornerstone of at-least-once delivery — broker must ack
 * before {@code published_at} is set; any failure (broker down, network)
 * leaves the row in pending state for the next poll to retry.
 *
 * <p>Partitioning: {@code aggregateId} (productId) is used as Kafka
 * partition key — all events for the same product go to the same
 * partition, preserving per-product ordering across consumers.
 *
 * <p>Failure isolation: a single event's publish failure does not abort
 * the batch — successful events still get {@code published_at} set, and
 * the failed event retries next poll. Consumer-side idempotency
 * (dedupe by {@code eventId} in payload) covers duplicates.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {

    private static final String HEADER_EVENT_TYPE = "eventType";

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.topic.product-events}")
    private String productEventsTopic;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxRepo.findTop100ByPublishedAtIsNullOrderByIdAsc();
        if (pending.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        int succeeded = 0;
        int failed = 0;
        for (OutboxEvent event : pending) {
            if (publish(event)) {
                event.setPublishedAt(now);
                succeeded++;
            } else {
                failed++;
            }
        }
        outboxRepo.saveAll(pending);
        log.info("[outbox] poll batch processed: succeeded={} failed={} (failed will retry next poll)",
                succeeded, failed);
    }

    private boolean publish(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                productEventsTopic,
                null,                          // partition: let Kafka hash partition key
                event.getAggregateId(),        // key: productId → same partition per product
                event.getPayload()             // value: JSON payload (already serialized)
        );
        record.headers().add(new RecordHeader(
                HEADER_EVENT_TYPE,
                event.getEventType().getBytes(StandardCharsets.UTF_8)
        ));

        try {
            SendResult<String, String> result = kafkaTemplate.send(record).get();
            log.info("[outbox] published outboxId={} type={} aggregate={} → topic={} partition={} offset={}",
                    event.getId(), event.getEventType(), event.getAggregateId(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[outbox] publish interrupted outboxId={} type={}", event.getId(), event.getEventType());
            return false;
        } catch (ExecutionException e) {
            log.error("[outbox] publish FAILED outboxId={} type={} — will retry next poll",
                    event.getId(), event.getEventType(), e.getCause());
            return false;
        }
    }
}
