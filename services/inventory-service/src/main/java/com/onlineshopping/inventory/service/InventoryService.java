package com.onlineshopping.inventory.service;

import com.onlineshopping.inventory.dto.InventoryResponse;
import com.onlineshopping.inventory.entity.Inventory;
import com.onlineshopping.inventory.entity.ProcessedEvent;
import com.onlineshopping.inventory.event.ProductCreatedEvent;
import com.onlineshopping.inventory.repository.InventoryRepository;
import com.onlineshopping.inventory.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Inventory business logic — driven by cross-service events.
 *
 * <p>L6.6 — Idempotency via {@code processed_events} dedup table.
 * The two writes (inventory + dedup record) are in the SAME
 * {@code @Transactional} → either both commit or both rollback,
 * never half-state.
 *
 * <p>Dedup key is {@code eventId} (UUID per event), not {@code productId}
 * — universal across all event types (ProductCreated /
 * ProductPriceUpdated / etc.). productId would only work for create.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepo;
    private final ProcessedEventRepository processedEventRepo;

    @Transactional
    public void createFromEvent(ProductCreatedEvent event) {
        // Idempotency guard — eventId already processed = consumer redelivery, skip
        if (processedEventRepo.existsById(event.eventId())) {
            log.info("Duplicate event delivery — skipping eventId={} productId={}",
                    event.eventId(), event.productId());
            return;
        }

        // Defensive: handle the case where inventory row already exists but no
        // dedup record (e.g. offset reset replay against pre-existing data,
        // manual ops). Without this, INSERT would throw DuplicateKeyException.
        if (inventoryRepo.existsById(event.productId())) {
            log.info("Inventory row already exists for productId={} — recording dedup only " +
                    "(likely offset reset replay)", event.productId());
        } else {
            Inventory inv = Inventory.builder()
                    .productId(event.productId())
                    .stockQuantity(0)
                    .build();
            inventoryRepo.save(inv);
            log.info("Inventory initialised: productId={} sku={}", event.productId(), event.sku());
        }

        ProcessedEvent dedup = ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(event.eventType())
                .build();
        processedEventRepo.save(dedup);
        log.debug("Dedup record committed: eventId={}", event.eventId());
    }

    @Transactional(readOnly = true)
    public Optional<InventoryResponse> getStock(Long productId) {
        return inventoryRepo.findById(productId)
                .map(inv -> new InventoryResponse(inv.getProductId(), inv.getStockQuantity()));
    }

}