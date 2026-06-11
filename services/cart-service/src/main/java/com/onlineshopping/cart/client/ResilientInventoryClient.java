package com.onlineshopping.cart.client;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resilience wrapper around the raw {@link InventoryClient} Feign interface.
 *
 * <p><b>Fallback strategy (different from ProductClient):</b>
 * Unlike product price, stock quantity is high-frequency-changing — even a
 * 30-second-old cached value is unreliable. cart_items does NOT snapshot
 * stock_quantity (only priceAtAddition + currency), so we have no safe
 * cache to fall back on.
 *
 * <p>Decision: <b>fail-fast 503 on infrastructure failure</b> for the
 * add-to-cart flow. Refusing to add is safer than allowing an add that
 * later turns out to be oversold at checkout time. Correctness > availability
 * for inventory.
 *
 * <p>For different scenarios:
 * <ul>
 *   <li>Add-to-cart (this code): fail-fast 503 — refuse rather than oversell
 *   <li>Browse product page (read-only): cached stock OK with "approximate" marker
 *   <li>Checkout (L9 saga): hard 503 with retry queue — financial criticality
 * </ul>
 *
 * <p>CB instance name {@code inventoryClient} is a SEPARATE instance from
 * {@code productClient} — failure isolation per downstream. Inventory dying
 * should not trip product's CB and vice versa.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResilientInventoryClient {

    private final InventoryClient inventoryClient;

    /**
     * Lookup stock, protected by the {@code inventoryClient} CB + Retry.
     * Aspect order (configured in application.yml) puts CB outside Retry so
     * one user request maps to one CB statistic regardless of retry count.
     */
    @CircuitBreaker(name = "inventoryClient", fallbackMethod = "getStockFallback")
    @Retry(name = "inventoryClient")
    public InventoryStock getStock(Long productId) {
        log.debug("ResilientInventoryClient: forwarding getStock(productId={}) to Feign",
                productId);
        return inventoryClient.getStock(productId);
    }

    /**
     * Fallback for {@link #getStock}. Distinguishes semantic 404 from
     * infrastructure failures — same pattern as ProductClient.
     */
    public InventoryStock getStockFallback(Long productId, Throwable cause) {
        // Semantic error — inventory record genuinely missing for this product.
        // Propagate the 404 distinction so CartService surfaces a proper
        // "inventory missing" response.
        if (cause instanceof FeignException.NotFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Inventory record missing for product: " + productId);
        }

        // Infrastructure failure — and we have no safe cache to fall back on.
        // Refuse the add rather than risk oversell.
        log.warn("ResilientInventoryClient fallback REJECTED for productId={} cause={} "
                        + "→ inventory service unavailable, refusing to verify stock",
                productId, cause.getClass().getSimpleName());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Inventory service unavailable; cannot verify stock");
    }
}
