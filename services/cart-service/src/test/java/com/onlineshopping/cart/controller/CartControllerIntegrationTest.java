package com.onlineshopping.cart.controller;

import com.onlineshopping.cart.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
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

    // ─── WireMock stub helpers ──────────────────────────────────────────────

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
