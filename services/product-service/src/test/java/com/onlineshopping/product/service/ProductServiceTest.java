package com.onlineshopping.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.onlineshopping.product.dto.CreateProductRequest;
import com.onlineshopping.product.entity.Product;
import com.onlineshopping.product.entity.ProductStatus;
import com.onlineshopping.product.event.ProductCreatedEvent;
import com.onlineshopping.product.repository.ProductRepository;
import com.onlineshopping.product.snowflake.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    @Mock
    private ProductRepository productRepo;
    @Mock
    private OutboxService outboxService;
    @Mock
    private SnowflakeIdGenerator snowflake;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepo, outboxService, snowflake);
    }

    @Test
    void create_assignsSnowflakeId_savesProduct_andEmitsOutboxEvent() throws Exception {
        // given
        long expectedId = 1834567890L;
        when(snowflake.nextId()).thenReturn(expectedId);
        when(productRepo.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateProductRequest req = new CreateProductRequest("Test Product", null, "TEST-SKU-001", 9900L, "USD", 100L, 5);

        // when
        Product saved = productService.create(req);

        // then — assertion bundle for one cohesive transactional behavior

        // 1. Snowflake assigned
        assertThat(saved.getId()).isEqualTo(expectedId);
        verify(snowflake).nextId();

        // 2. Product saved with correct state
        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepo).save(productCaptor.capture());
        Product savedArg = productCaptor.getValue();
        assertThat(savedArg.getName()).isEqualTo("Test Product");
        assertThat(savedArg.getSku()).isEqualTo("TEST-SKU-001");
        assertThat(savedArg.getPriceCents()).isEqualTo(9900L);
        assertThat(savedArg.getCurrency()).isEqualTo("USD");
        assertThat(savedArg.getStatus()).isEqualTo(ProductStatus.DRAFT);

        // 3. Outbox event recorded with correct payload
        ArgumentCaptor<ProductCreatedEvent> eventCaptor = ArgumentCaptor.forClass(ProductCreatedEvent.class);
        verify(outboxService).record(eq("ProductCreated"), eq(String.valueOf(expectedId)), eventCaptor.capture());
        ProductCreatedEvent event = eventCaptor.getValue();

        assertThat(event.eventType()).isEqualTo("ProductCreated");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.productId()).isEqualTo(expectedId);
        assertThat(event.name()).isEqualTo("Test Product");
        assertThat(event.sku()).isEqualTo("TEST-SKU-001");
        assertThat(event.priceCents()).isEqualTo(9900L);
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.status()).isEqualTo("DRAFT");
    }

    @Test
    void create_outboxFailure_propagatesException() {
        when(snowflake.nextId()).thenReturn(1834567890L);
        when(productRepo.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        // BUT outbox throws (simulating: Kafka down, JSON serialization error,
        // OutboxService propagation violation, etc.)
        doThrow(new RuntimeException("simulated outbox failure")).when(outboxService)
                .record(anyString(), anyString(), any());

        CreateProductRequest req = new CreateProductRequest("Test Product", null, "TEST-SKU-001", 9900L, "USD", 100L, 5);
        // when + then — exception 必須 propagate，唔可以 silently swallow
        assertThatThrownBy(() -> productService.create(req)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated outbox failure");

        // Verify call sequence: save 真係被嘗試過 (在 outbox 失敗之前)
        // 證明 service code 冇 wrap try-catch silently swallow exception
        verify(productRepo).save(any(Product.class));
        verify(outboxService).record(anyString(), anyString(), any());
    }

    // TODO 3: findById_existingProduct_returns
    @Test
    void findById_existingProduct_returnsProduct() {
        // given
        long expectedId = 1834567890L;
        Product p = Product.builder()
                .id(1834567890L)
                .name("Test Product")
                .sku("TEST-SKU-001")
                .priceCents(9900L)
                .currency("USD")
                .status(ProductStatus.DRAFT)
                .build();
        when(productRepo.findById(expectedId)).thenReturn(Optional.of(p));

        // act
        Product actual = productService.findById(expectedId);

        // Assert
        assertThat(actual.getId()).isEqualTo(expectedId);
        assertThat(actual.getName()).isEqualTo("Test Product");
        assertThat(actual.getSku()).isEqualTo("TEST-SKU-001");
        assertThat(actual.getPriceCents()).isEqualTo(9900L);
        assertThat(actual.getCurrency()).isEqualTo("USD");
        assertThat(actual.getStatus()).isEqualTo(ProductStatus.DRAFT);

    }

    @Test
    void findById_softDeletedProduct_throwsResponseStatusException() {
        // given
        Product softDeleted = Product.builder()
                .id(123L)
                .build();
        softDeleted.softDelete();   // sets deletedAt to non-null
        when(productRepo.findById(123L)).thenReturn(Optional.of(softDeleted));

        // when + then
        assertThatThrownBy(() -> productService.findById(123L)).isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }
}
