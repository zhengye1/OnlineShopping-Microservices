package com.onlineshopping.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineshopping.product.dto.CreateProductRequest;
import com.onlineshopping.product.entity.Product;
import com.onlineshopping.product.entity.ProductStatus;
import com.onlineshopping.product.event.ProductCreatedEvent;
import com.onlineshopping.product.repository.ProductRepository;
import com.onlineshopping.product.snowflake.SnowflakeIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {
    private final ProductRepository productRepo;
    private final OutboxService outboxService;
    private final SnowflakeIdGenerator snowflake;

    public ProductService(ProductRepository productRepo,
                          OutboxService outboxService,
                          SnowflakeIdGenerator snowflake) {
        this.productRepo = productRepo;
        this.outboxService = outboxService;
        this.snowflake = snowflake;
    }
    /**
     * 創建 Product + 寫 outbox event，原子操作。
     *
     * <p>Snowflake ID 喺 application layer assign (唔靠 DB auto-inc)，
     * 確保 ProductCreatedEvent payload 喺 INSERT 之前已知 productId →
     * outbox row 同 entity row 同一 transaction commit。
     */
    @Transactional
    public Product create(CreateProductRequest req) {
        Product p = Product.builder()
                .id(snowflake.nextId())
                .name(req.name())
                .description(req.description())
                .sku(req.sku())
                .priceCents(req.priceCents())
                .currency(req.currency())
                .categoryId(req.categoryId())
                .status(ProductStatus.DRAFT)
                .stockQuantity(req.initialStock() != null ? req.initialStock() : 0)
                .build();

        // 2. Save entity
        Product saved = productRepo.save(p);

        // 3. Build + record event (same transaction, MANDATORY enforcement)
        ProductCreatedEvent event = ProductCreatedEvent.from(saved);
        outboxService.record("ProductCreated", String.valueOf(saved.getId()), event);
        return saved;
    }
    @Transactional(readOnly = true)
    public Product findById(Long id) {
        return productRepo.findById(id)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Product not found"));
    }
}
