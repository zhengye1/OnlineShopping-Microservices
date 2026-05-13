package com.onlineshopping.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineshopping.product.entity.OutboxEvent;
import com.onlineshopping.product.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records outbox events transactionally with the originating business write.
 *
 * <p><b>Critical invariant — {@link Propagation#MANDATORY}</b>:
 * this method MUST be called from inside an existing {@code @Transactional}
 * scope. Spring will throw {@code IllegalTransactionStateException} otherwise.
 *
 * <p>Why MANDATORY rather than REQUIRED? REQUIRED would auto-create a new
 * transaction if none exists — silently allowing the caller to lose the
 * atomicity guarantee. MANDATORY surfaces the misuse loudly at runtime.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    /**
     * Record an outbox event in the current transaction.
     *
     * @param eventType   event discriminator (e.g. {@code UserCreatedEvent.TYPE})
     * @param aggregateId identifier of the aggregate that produced the event
     * @param payload     event body (will be Jackson-serialized to JSON)
     * @throws IllegalStateException if not called inside a {@code @Transactional} scope
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String eventType, String aggregateId, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Wrap as runtime — transaction will roll back, taking the entity write with it.
            throw new IllegalStateException("Failed to serialize outbox payload type=" + eventType, e);
        }

        OutboxEvent event = OutboxEvent.builder()
                .eventType(eventType)
                .aggregateId(aggregateId)
                .payload(json)
                .build();
        outboxRepo.save(event);
        log.debug("Outbox recorded: type={} aggregate={}", eventType, aggregateId);
    }
}
