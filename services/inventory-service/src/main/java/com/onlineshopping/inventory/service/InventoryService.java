package com.onlineshopping.inventory.service;

import com.onlineshopping.inventory.entity.Inventory;
import com.onlineshopping.inventory.event.ProductCreatedEvent;
import com.onlineshopping.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory business logic — driven by cross-service events.
 *
 * <p>L6: only handles {@code ProductCreatedEvent} → initialise an
 * inventory row with stock_quantity = 0. Later lessons add
 * {@code ProductPriceUpdatedEvent}, {@code ProductDeletedEvent},
 * {@code OrderPlacedEvent} (decrement stock), etc.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepo;

    @Transactional
    public void createFromEvent(ProductCreatedEvent event) {
        Inventory inv = Inventory.builder()
                .productId(event.productId())
                .stockQuantity(0)
                .build();
        inventoryRepo.save(inv);
        log.info("Inventory initialised: productId={} sku={}", event.productId(), event.sku());
    }
}