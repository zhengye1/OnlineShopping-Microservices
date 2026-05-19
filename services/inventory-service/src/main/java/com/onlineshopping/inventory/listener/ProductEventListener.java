package com.onlineshopping.inventory.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineshopping.inventory.event.ProductCreatedEvent;
import com.onlineshopping.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Listens to {@code product-events} topic and dispatches to handlers
 * based on the {@code eventType} header.
 *
 * <p>L6 — only handles {@code ProductCreated}. Future events route
 * via the same switch.
 *
 * <p>Ack discipline: {@code Acknowledgment.acknowledge()} is called
 * <b>after</b> the business {@code @Transactional} commits. Any
 * exception before {@code acknowledge()} leaves the offset unmoved,
 * so the next poll redelivers — at-least-once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductEventListener {

    private static final String HEADER_EVENT_TYPE = "eventType";
    private static final String EVENT_PRODUCT_CREATED = "ProductCreated";

    private final ObjectMapper objectMapper;
    private final InventoryService inventoryService;

    @KafkaListener(
            topics = "${app.kafka.topic.product-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onProductEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventType = readHeader(record, HEADER_EVENT_TYPE);
        log.info("Received event type={} partition={} offset={} key={}",
                eventType, record.partition(), record.offset(), record.key());

        try {
            if ((eventType == null ? "" : eventType).equals(EVENT_PRODUCT_CREATED)) {
                handleProductCreated(record.value());
            } else {
                log.info("Ignoring unhandled event type: {}", eventType);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed processing event type={} offset={} — NOT committing offset, will retry",
                    eventType, record.offset(), e);
            throw new RuntimeException("Listener processing failed", e);
        }
    }

    private void handleProductCreated(String payload) throws Exception {
        ProductCreatedEvent event = objectMapper.readValue(payload, ProductCreatedEvent.class);
        inventoryService.createFromEvent(event);
    }

    private String readHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}