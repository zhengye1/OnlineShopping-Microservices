package com.onlineshopping.product.controller;

import com.onlineshopping.product.AbstractIntegrationTest;
import com.onlineshopping.product.entity.Category;
import com.onlineshopping.product.repository.CategoryRepository;
import com.onlineshopping.product.repository.OutboxEventRepository;
import com.onlineshopping.product.repository.ProductRepository;
import com.onlineshopping.product.snowflake.SnowflakeIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ProductControllerIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private ProductRepository productRepo;
    @Autowired private CategoryRepository categoryRepo;
    @Autowired private OutboxEventRepository outboxRepo;
    @Autowired private SnowflakeIdGenerator snowflake;

    private Long categoryId;

    @BeforeEach
    void seedCategory() {
        Category cat = Category.builder()
                .id(snowflake.nextId())
                .name("TestCategory")
                .slug("test-cat-" + System.nanoTime())
                .sortOrder(0)
                .build();
        categoryRepo.save(cat);
        this.categoryId = cat.getId();
    }

    @AfterEach
    void cleanup() {
        outboxRepo.deleteAll();
        productRepo.deleteAll();
        categoryRepo.deleteAll();
    }

    @Test
    void create_validRequest_returns201_andResponseBody() throws Exception {
        String body = """
          {
            "name": "Test Product",
            "sku": "TEST-001",
            "priceCents": 9900,
            "currency": "USD",
            "categoryId": %d,
            "initialStock": 100
          }
          """.formatted(categoryId);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Test Product"))
                .andExpect(jsonPath("$.sku").value("TEST-001"))
                .andExpect(jsonPath("$.priceCents").value(9900))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // DB-side verification
        assertThat(productRepo.findAll()).hasSize(1);
    }

    @Test
    void create_invalidSku_returns400_withValidationError() throws Exception {
        String body = """
          {
            "name": "Test Product",
            "sku": "ab",
            "priceCents": 9900,
            "currency": "USD",
            "categoryId": %d,
            "initialStock": 100
          }
          """.formatted(categoryId);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[?(@.field=='sku')]").exists());

        // DB-side: nothing persisted
        assertThat(productRepo.findAll()).isEmpty();
        assertThat(outboxRepo.findAll()).isEmpty();
    }

    @Test
    void create_thenGetById_returnsProduct() throws Exception {
        String body = """
          {
            "name":"Round Trip",
            "sku":"RT-001",
            "priceCents":9999,
            "currency":"USD",
            "categoryId":%d
          }
          """.formatted(categoryId);

        var createResult = mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        Long productId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(get("/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId))
                .andExpect(jsonPath("$.sku").value("RT-001"))
                .andExpect(jsonPath("$.priceCents").value(9999));
    }

    @Test
    void create_writesOutboxRow_withCorrectPayload() throws Exception {
        String body = """
          {
            "name": "Outbox Test Product",
            "sku": "OUTBOX-TEST-001",
            "priceCents": 12345,
            "currency": "USD",
            "categoryId": %d,
            "initialStock": 50
          }
          """.formatted(categoryId);

        var result = mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        // Parse productId from response
        String responseJson = result.getResponse().getContentAsString();
        Long productId = objectMapper.readTree(responseJson).get("id").asLong();

        // ⭐ DB-side outbox verification
        var outboxRows = outboxRepo.findAll();
        assertThat(outboxRows).hasSize(1);

        var event = outboxRows.getFirst();
        assertThat(event.getEventType()).isEqualTo("ProductCreated");
        assertThat(event.getAggregateId()).isEqualTo(String.valueOf(productId));
        assertThat(event.getPayload())
                .contains("\"eventType\":\"ProductCreated\"")
                .contains("\"eventVersion\":1")
                .contains("\"sku\":\"OUTBOX-TEST-001\"")
                .contains("\"priceCents\":12345");
    }

    @Test
    void getById_nonExistent_returns404() throws Exception {
        long nonExistentId = 99999999999L;        // arbitrary unused Snowflake-like ID

        mockMvc.perform(get("/products/" + nonExistentId))
                .andExpect(status().isNotFound());
    }

}
