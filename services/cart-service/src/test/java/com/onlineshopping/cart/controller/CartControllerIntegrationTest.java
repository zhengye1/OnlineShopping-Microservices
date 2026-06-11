package com.onlineshopping.cart.controller;

import com.onlineshopping.cart.AbstractIntegrationTest;
import com.onlineshopping.cart.entity.CartItem;
import com.onlineshopping.cart.repository.CartItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration test for POST /cart/items.
 *
 * <p>Verifies the request flow: Security filter chain → CartController →
 * CartService → ProductClient / InventoryClient (WireMock-stubbed) → JPA →
 * MySQL (Testcontainers).
 *
 * <p>Test scenarios target the L7 main flow:
 * <ul>
 *   <li>happyPath — product OK + stock OK → 201 Created
 *   <li>productNotFound — Feign 404 from product-service → 404 Not Found
 *   <li>insufficientStock — stock < requested → 409 Conflict
 *   <li>unauthenticated — no JWT → 401 Unauthorized
 * </ul>
 */
class CartControllerIntegrationTest extends AbstractIntegrationTest {

    private static final long TEST_USER_ID = 42L;
    private static final long TEST_PRODUCT_ID = 100L;

    @Autowired
    private CartItemRepository cartItemRepo;

    // ─── WireMock stub helpers ──────────────────────────────────────────────

    /** Stub product-service GET /products/{id} → 503 to simulate downstream outage. */
    private void stubProductServiceDown(long productId) {
        wireMock.stubFor(get(urlPathEqualTo("/products/" + productId))
                .willReturn(aResponse().withStatus(503)));
    }

    /** Stub inventory-service GET /inventory/{id} → 503 to simulate downstream outage. */
    private void stubInventoryServiceDown(long productId) {
        wireMock.stubFor(get(urlPathEqualTo("/inventory/" + productId))
                .willReturn(aResponse().withStatus(503)));
    }

    /** Stub product-service GET /products/{id} → 200 with a realistic body. */
    private void stubProductExists(long productId, long priceCents, String currency) {
        wireMock.stubFor(get(urlPathEqualTo("/products/" + productId))
                .willReturn(okJson("""
                        {
                          "id": %d,
                          "name": "Test Product",
                          "sku": "TEST-%d",
                          "priceCents": %d,
                          "currency": "%s",
                          "status": "ACTIVE"
                        }
                        """.formatted(productId, productId, priceCents, currency))));
    }

    /** Stub product-service GET /products/{id} → 404. */
    private void stubProductNotFound(long productId) {
        wireMock.stubFor(get(urlPathEqualTo("/products/" + productId))
                .willReturn(aResponse().withStatus(404)));
    }

    /** Stub inventory-service GET /inventory/{productId} → 200 with stock level. */
    private void stubInventoryHasStock(long productId, int stockQuantity) {
        wireMock.stubFor(get(urlPathEqualTo("/inventory/" + productId))
                .willReturn(okJson("""
                        {
                          "productId": %d,
                          "stockQuantity": %d
                        }
                        """.formatted(productId, stockQuantity))));
    }

    /** Build request body for POST /cart/items. */
    private String addCartItemRequest(long productId, int quantity) {
        return """
               {
                 "productId": %d,
                 "quantity": %d
               }
               """.formatted(productId, quantity);
    }

    // ─── Tests ──────────────────────────────────────────────────────────────

    @Test
    void addItem_happyPath_returns201_andPersistsCartItem() throws Exception {
        // GIVEN: product exists at $99.99 CAD, inventory has 50 units
        stubProductExists(TEST_PRODUCT_ID, 9999L, "CAD");
        stubInventoryHasStock(TEST_PRODUCT_ID, 50);

        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = addCartItemRequest(TEST_PRODUCT_ID, 2);

        mockMvc.perform(post("/cart/items")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(TEST_USER_ID))
                .andExpect(jsonPath("$.productId").value(TEST_PRODUCT_ID))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.priceAtAddition").value(9999L))
                .andExpect(jsonPath("$.currency").value("CAD"));

        // Verify FeignAuthForwardInterceptor forwarded the Bearer token to downstream.
        wireMock.verify(getRequestedFor(urlPathEqualTo("/products/" + TEST_PRODUCT_ID))
                .withHeader("Authorization", equalTo(authHeader)));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/inventory/" + TEST_PRODUCT_ID))
                .withHeader("Authorization", equalTo(authHeader)));
    }

    @Test
    void addItem_productNotFound_returns404() throws Exception {
        // GIVEN: product-service returns 404 for this productId.
        stubProductNotFound(TEST_PRODUCT_ID);
        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = addCartItemRequest(TEST_PRODUCT_ID, 2);
        mockMvc.perform(post("/cart/items")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isNotFound());
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/inventory/" + TEST_PRODUCT_ID)));
    }

    @Test
    void addItem_insufficientStock_returns409() throws Exception {
        // GIVEN: product OK, but inventory only has 1 unit while user wants 2.
        stubProductExists(TEST_PRODUCT_ID, 9999L, "CAD");
        stubInventoryHasStock(TEST_PRODUCT_ID, 1);
        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = addCartItemRequest(TEST_PRODUCT_ID, 2);
        mockMvc.perform(post("/cart/items")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Insufficient stock")));
    }

    // ─── L8 Phase 2: Resilience4j fallback scenarios ────────────────────────

    @Test
    void addItem_productServiceDown_existingCart_fallsBackToCachedPrice() throws Exception {
        // GIVEN: user already has this product in cart with a snapshotted price.
        cartItemRepo.save(CartItem.builder()
                .userId(TEST_USER_ID)
                .productId(TEST_PRODUCT_ID)
                .quantity(3)
                .priceAtAddition(8000L)       // snapshot from a previous add — IMPORTANT: stale
                .currency("USD")
                .build());

        // AND: product-service is down (503).
        stubProductServiceDown(TEST_PRODUCT_ID);
        // BUT: inventory-service is healthy and has stock.
        stubInventoryHasStock(TEST_PRODUCT_ID, 50);

        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = addCartItemRequest(TEST_PRODUCT_ID, 2);

        // WHEN: user adds 2 more units.
        // THEN: CB fallback HIT — degraded mode succeeds using cached snapshot;
        // priceAtAddition stays 8000 (the cached value, NOT the live price).
        mockMvc.perform(post("/cart/items")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(5))           // 3 + 2 = 5
                .andExpect(jsonPath("$.priceAtAddition").value(8000)) // cached snapshot preserved
                .andExpect(jsonPath("$.currency").value("USD"));      // cached currency preserved
    }

    @Test
    void addItem_transientError_recoversAfterRetry() throws Exception {
        // GIVEN: product-service fails twice with 503, then succeeds on 3rd try.
        // This simulates a transient hiccup (load spike / brief network issue)
        // that retry should recover from automatically.
        //
        // WireMock scenarios = stateful stub graph. We define 3 states:
        //   "started" → "after-first-fail" → "after-second-fail"
        // and chain stubs to walk through them. Resilience4j's 3 attempts
        // (1 initial + 2 retries) should hit all 3 states.
        wireMock.stubFor(get(urlPathEqualTo("/products/" + TEST_PRODUCT_ID))
                .inScenario("transient-503")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("after-first-fail"));
        wireMock.stubFor(get(urlPathEqualTo("/products/" + TEST_PRODUCT_ID))
                .inScenario("transient-503")
                .whenScenarioStateIs("after-first-fail")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("after-second-fail"));
        wireMock.stubFor(get(urlPathEqualTo("/products/" + TEST_PRODUCT_ID))
                .inScenario("transient-503")
                .whenScenarioStateIs("after-second-fail")
                .willReturn(okJson("""
                        {
                          "id": %d, "name": "Test", "sku": "T-1",
                          "priceCents": 9999, "currency": "CAD", "status": "ACTIVE"
                        }
                        """.formatted(TEST_PRODUCT_ID))));
        stubInventoryHasStock(TEST_PRODUCT_ID, 50);

        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = addCartItemRequest(TEST_PRODUCT_ID, 2);

        // WHEN: first-time add (no cart row yet — no fallback path possible).
        // THEN: retry rescues the call; user sees 201 with live price, NOT 503.
        mockMvc.perform(post("/cart/items")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceAtAddition").value(9999L));

        // Verify product-service was actually hit 3 times — proves retry ran.
        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/products/" + TEST_PRODUCT_ID)));
    }

    @Test
    void addItem_productServiceDown_newCart_returns503() throws Exception {
        // GIVEN: product-service is down (503), user has NO prior cart row.
        stubProductServiceDown(TEST_PRODUCT_ID);

        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = addCartItemRequest(TEST_PRODUCT_ID, 2);

        // WHEN: first-time add attempted.
        // THEN: fallback MISS — no cached snapshot exists, so we refuse rather
        // than fabricate a price. User sees a clean 503 with explanatory detail.
        mockMvc.perform(post("/cart/items")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("Product service unavailable")));

        // Defense-in-depth: inventory-service was NOT called — short-circuit on
        // fallback miss before stock check (avoids unnecessary downstream load).
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/inventory/" + TEST_PRODUCT_ID)));
    }

    @Test
    void addItem_inventoryServiceDown_returns503_evenWithExistingCart() throws Exception {
        // GIVEN: user has an existing cart row (we DO have cached priceAtAddition).
        // BUT: inventory degraded mode is fail-fast — unlike product, we can't
        // trust a stale stock snapshot. cart_items never snapshotted stock.
        cartItemRepo.save(CartItem.builder()
                .userId(TEST_USER_ID)
                .productId(TEST_PRODUCT_ID)
                .quantity(1)
                .priceAtAddition(5000L)
                .currency("CAD")
                .build());

        // Product service healthy, inventory service down.
        stubProductExists(TEST_PRODUCT_ID, 5000L, "CAD");
        stubInventoryServiceDown(TEST_PRODUCT_ID);

        String authHeader = mockJwtFor(TEST_USER_ID);
        String body = addCartItemRequest(TEST_PRODUCT_ID, 1);

        // THEN: 503 — we refuse rather than risk oversell at checkout. Even
        // though we have a cart row (which works for product fallback), there
        // is no safe stock fallback.
        mockMvc.perform(post("/cart/items")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("Inventory service unavailable")));
    }

    @Test
    void addItem_unauthenticated_returns401() throws Exception {
        // GIVEN: no JWT mocked, no Authorization header sent.
        // Spring Security BearerTokenAuthenticationFilter rejects at the
        // filter layer — controller never invoked, downstream never called.
        mockMvc.perform(post("/cart/items")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // Defense-in-depth: prove no downstream calls leaked through.
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }
}
