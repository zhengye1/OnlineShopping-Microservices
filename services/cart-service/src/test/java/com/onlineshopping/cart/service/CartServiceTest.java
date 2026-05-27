package com.onlineshopping.cart.service;

import com.onlineshopping.cart.client.InventoryClient;
import com.onlineshopping.cart.client.InventoryStock;
import com.onlineshopping.cart.client.ProductClient;
import com.onlineshopping.cart.client.ProductSummary;
import com.onlineshopping.cart.dto.AddCartItemRequest;
import com.onlineshopping.cart.dto.CartItemResponse;
import com.onlineshopping.cart.entity.CartItem;
import com.onlineshopping.cart.entity.CartItemId;
import com.onlineshopping.cart.repository.CartItemRepository;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {
    @Mock
    private CartItemRepository cartItemRepo;
    @Mock
    private ProductClient productClient;
    @Mock
    private InventoryClient inventoryClient;

    @InjectMocks
    private CartService cartService;

    private static final Long USER_ID = 42L;
    private static final Long PRODUCT_ID = 100L;
    private static ProductSummary sampleProduct() {
        return new ProductSummary(PRODUCT_ID, "Widget", "WID-001", 9900L, "CAD", "ACTIVE");
    }

    private static InventoryStock sampleStock(int qty) {
        return new InventoryStock(PRODUCT_ID, qty);
    }

    private static FeignException.NotFound notFound(String url) {
        Request req = Request.create(Request.HttpMethod.GET, url, Map.of(), null, new RequestTemplate());
        return new FeignException.NotFound("not found", req, null, null);
    }

    // ============================================================
    //  CASE 1 — Happy path: new cart row, snapshots price + currency
    // ============================================================
    @Test
    void happyPath_insertsNewItem_snapshotsPriceAndCurrency() {
        AddCartItemRequest req = new AddCartItemRequest(PRODUCT_ID, 2);
        when(productClient.findById(PRODUCT_ID)).thenReturn(sampleProduct());
        when(inventoryClient.getStock(PRODUCT_ID)).thenReturn(sampleStock(50));
        when(cartItemRepo.findById(new CartItemId(USER_ID, PRODUCT_ID))).thenReturn(Optional.empty());
        when(cartItemRepo.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));

        CartItemResponse resp = cartService.addItem(USER_ID, req);

        ArgumentCaptor<CartItem> savedCap = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepo).save(savedCap.capture());

        CartItem saved = savedCap.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getPriceAtAddition()).isEqualTo(9900L);
        assertThat(saved.getCurrency()).isEqualTo("CAD");

        assertThat(resp.priceAtAddition()).isEqualTo(9900L);
        assertThat(resp.currency()).isEqualTo("CAD");
    }
    // ============================================================
    //  CASE 2 — Upsert: same product again → qty bump, price KEPT
    // ============================================================
    @Test
    void upsert_incrementsQuantity_keepsOriginalPrice() {
        CartItem existing = CartItem.builder()
                .userId(USER_ID)
                .productId(PRODUCT_ID)
                .quantity(3)
                .priceAtAddition(8000L)              // 原 snapshot 價，唔同 current
                .currency("USD")                       // 原 currency
                .build();

        AddCartItemRequest req = new AddCartItemRequest(PRODUCT_ID, 2);
        // Product price 已經升 9900 (current)，但 cart 應該 keep 原 8000
        when(productClient.findById(PRODUCT_ID)).thenReturn(sampleProduct());
        when(inventoryClient.getStock(PRODUCT_ID)).thenReturn(sampleStock(50));
        when(cartItemRepo.findById(new CartItemId(USER_ID, PRODUCT_ID))).thenReturn(Optional.of(existing));
        when(cartItemRepo.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));

        cartService.addItem(USER_ID, req);

        ArgumentCaptor<CartItem> savedCap = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepo).save(savedCap.capture());

        CartItem saved = savedCap.getValue();
        assertThat(saved.getQuantity()).isEqualTo(5);                       // 3 + 2
        assertThat(saved.getPriceAtAddition()).isEqualTo(8000L);           // ⭐ 原價 keep
        assertThat(saved.getCurrency()).isEqualTo("USD");                   // ⭐ 原 currency keep
    }
    // ============================================================
    //  TODO — 你寫呢 3 case
    // ============================================================

    // CASE 3 — insufficientStock_throws409
    // Hint:
    //   - existing cart qty = 8, req.quantity = 10, stock = 15
    //   - 8 + 10 = 18 > 15 → 409
    //   - assertThatThrownBy(() -> cartService.addItem(...))
    //         .isInstanceOf(ResponseStatusException.class)
    //         .hasMessageContaining("409")  OR check getStatusCode()
    //   - verify(cartItemRepo, never()).save(any())   // 確保 no write 發生
    @Test
    void insufficientStock_throws409(){
        CartItem existing = CartItem.builder()
                .userId(USER_ID)
                .productId(PRODUCT_ID)
                .quantity(8)
                .priceAtAddition(9900L)
                .currency("CAD")
                .build();
        AddCartItemRequest req = new AddCartItemRequest(PRODUCT_ID, 10);
        when(productClient.findById(PRODUCT_ID)).thenReturn(sampleProduct());
        when(inventoryClient.getStock(PRODUCT_ID)).thenReturn(sampleStock(15));
        when(cartItemRepo.findById(new CartItemId(USER_ID, PRODUCT_ID))).thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->cartService.addItem(USER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(cartItemRepo, never()).save(any());

    }
    // CASE 4 — productNotFound_throws404
    @Test
    void productNotFound_throws404(){
        when(productClient.findById(any())).thenThrow(notFound("/products/100"));
        AddCartItemRequest req = new AddCartItemRequest(PRODUCT_ID, 10);
        assertThatThrownBy(() ->cartService.addItem(USER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(inventoryClient, never()).getStock(any());   // 證明 short-circuit
        verify(cartItemRepo, never()).save(any());
    }
    // CASE 5 — inventoryNotFound_throws404
    @Test
    void inventoryNotFound_throws404(){
        AddCartItemRequest req = new AddCartItemRequest(PRODUCT_ID, 10);
        when(productClient.findById(PRODUCT_ID)).thenReturn(sampleProduct());
        when(inventoryClient.getStock(any())).thenThrow(notFound("/inventory/100"));
        assertThatThrownBy(() ->cartService.addItem(USER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(cartItemRepo, never()).save(any());
    }
}
