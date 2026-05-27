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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * L7 Phase 3: cart business logic — fans out to product-service + inventory-service
 * via Feign clients, applies stock + upsert rules, persists locally.
 *
 * <p>Upsert pattern: same (user, product) → increment quantity, keep original
 * priceAtAddition snapshot (Amazon model — user sees stable price between
 * add-to-cart and checkout).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {

    private final CartItemRepository cartItemRepo;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;

    @Transactional
    public CartItemResponse addItem(Long userId, AddCartItemRequest req) {
        // 1. Validate product exists + get price snapshot (forwards user JWT via interceptor)
        ProductSummary product;
        try {
            product = productClient.findById(req.productId());
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Product not found: " + req.productId());
        }

        // 2. Check stock availability
        InventoryStock stock;
        try {
            stock = inventoryClient.getStock(req.productId());
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Inventory record missing for product: " + req.productId());
        }

        int existingQty = cartItemRepo.findById(new CartItemId(userId, req.productId()))
                .map(CartItem::getQuantity)
                .orElse(0);
        int totalRequested = existingQty + req.quantity();
        if (totalRequested > stock.stockQuantity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Insufficient stock: requested total " + totalRequested
                            + " exceeds available " + stock.stockQuantity());
        }

        // 3. Upsert — first-add snapshots price+currency; subsequent adds only bump qty
        Optional<CartItem> existing = cartItemRepo.findById(new CartItemId(userId, req.productId()));
        CartItem saved;
        if (existing.isPresent()) {
            CartItem item = existing.get();
            item.setQuantity(item.getQuantity() + req.quantity());
            saved = cartItemRepo.save(item);
            log.info("Cart upsert (increment): userId={} productId={} qty {}->{}",
                    userId, req.productId(), existingQty, saved.getQuantity());
        } else {
            CartItem item = CartItem.builder()
                    .userId(userId)
                    .productId(req.productId())
                    .quantity(req.quantity())
                    .priceAtAddition(product.priceCents())
                    .currency(product.currency())
                    .build();
            saved = cartItemRepo.save(item);
            log.info("Cart insert (new): userId={} productId={} qty={} price={} {}",
                    userId, req.productId(), saved.getQuantity(),
                    saved.getPriceAtAddition(), saved.getCurrency());
        }

        return CartItemResponse.from(saved);
    }
}
