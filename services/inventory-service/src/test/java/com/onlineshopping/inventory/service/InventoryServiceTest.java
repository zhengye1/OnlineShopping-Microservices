package com.onlineshopping.inventory.service;

import com.onlineshopping.inventory.dto.InventoryResponse;
import com.onlineshopping.inventory.entity.Inventory;
import com.onlineshopping.inventory.entity.ProcessedEvent;
import com.onlineshopping.inventory.event.ProductCreatedEvent;
import com.onlineshopping.inventory.repository.InventoryRepository;
import com.onlineshopping.inventory.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepo;
    @Mock
    private ProcessedEventRepository processedEventRepo;

    @InjectMocks
    private InventoryService inventoryService;

    private static ProductCreatedEvent sampleEvent() {
        return sampleEvent(50);
    }

    private static ProductCreatedEvent sampleEvent(Integer initialStock) {
        return new ProductCreatedEvent(
                "evt-uuid-1",
                "ProductCreated",
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                100L,
                "Test Product",
                "TEST-SKU",
                9900L,
                "CAD",
                42L,
                "DRAFT",
                initialStock
        );
    }

    @Test
    void happyPath_savesInventoryAndDedupRecord() {
        ProductCreatedEvent event = sampleEvent();
        when(processedEventRepo.existsById("evt-uuid-1")).thenReturn(false);
        when(inventoryRepo.existsById(100L)).thenReturn(false);

        inventoryService.createFromEvent(event);

        ArgumentCaptor<Inventory> invCap = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepo).save(invCap.capture());
        assertThat(invCap.getValue().getProductId()).isEqualTo(100L);
        assertThat(invCap.getValue().getStockQuantity()).isEqualTo(50);     // L7: propagated from event

        ArgumentCaptor<ProcessedEvent> dedupCap = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepo).save(dedupCap.capture());
        assertThat(dedupCap.getValue().getEventId()).isEqualTo("evt-uuid-1");
        assertThat(dedupCap.getValue().getEventType()).isEqualTo("ProductCreated");
    }

    @Test
    void backwardCompat_eventWithoutInitialStock_fallsBackToZero() {
        // Simulates a pre-L7 producer message where initialStock field is absent
        // (Jackson deserializes missing field as null on Integer type)
        ProductCreatedEvent event = sampleEvent(null);
        when(processedEventRepo.existsById("evt-uuid-1")).thenReturn(false);
        when(inventoryRepo.existsById(100L)).thenReturn(false);

        inventoryService.createFromEvent(event);

        ArgumentCaptor<Inventory> invCap = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepo).save(invCap.capture());
        assertThat(invCap.getValue().getStockQuantity()).isEqualTo(0);
    }

    @Test
    void duplicateEvent_skipsAllWrites() {
        ProductCreatedEvent event = sampleEvent();
        when(processedEventRepo.existsById("evt-uuid-1")).thenReturn(true);

        inventoryService.createFromEvent(event);

        // First guard hit — neither write happens
        verify(inventoryRepo, never()).save(any());
        verify(inventoryRepo, never()).existsById(any());      // 連 second guard 都唔 trigger
        verify(processedEventRepo, never()).save(any());
    }

    @Test
    void inventoryExistsButNoDedup_skipsInventoryAndOnlyRecordsDedup() {
        ProductCreatedEvent event = sampleEvent();
        when(processedEventRepo.existsById("evt-uuid-1")).thenReturn(false);
        when(inventoryRepo.existsById(100L)).thenReturn(true);

        inventoryService.createFromEvent(event);

        // Second guard hit — inventory 保持原狀
        verify(inventoryRepo, never()).save(any());

        // 但 dedup 仍然要寫 (將 torn state 修補)
        verify(processedEventRepo).save(any(ProcessedEvent.class));
    }

    @Test
    void getStock_returnsResponse_whenInventoryExists() {
        Inventory inv = Inventory.builder()
                .productId(100L)
                .stockQuantity(42)
                .build();
        when(inventoryRepo.findById(100L)).thenReturn(Optional.of(inv));

        Optional<InventoryResponse> result = inventoryService.getStock(100L);

        assertThat(result).isPresent();
        assertThat(result.get().productId()).isEqualTo(100L);
        assertThat(result.get().stockQuantity()).isEqualTo(42);
    }

    @Test
    void getStock_returnsEmpty_whenInventoryMissing() {
        when(inventoryRepo.findById(999L)).thenReturn(Optional.empty());

        Optional<InventoryResponse> result = inventoryService.getStock(999L);

        assertThat(result).isEmpty();
    }
}
