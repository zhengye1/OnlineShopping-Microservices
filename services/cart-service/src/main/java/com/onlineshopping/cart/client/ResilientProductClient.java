package com.onlineshopping.cart.client;

import com.onlineshopping.cart.entity.CartItem;
import com.onlineshopping.cart.entity.CartItemId;
import com.onlineshopping.cart.repository.CartItemRepository;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Resilience wrapper around the raw {@link ProductClient} Feign interface.
 *
 * <p><b>Fallback strategy (Phase 2):</b> when CB is OPEN (or downstream throws),
 * we degrade by consulting our own {@code cart_items} table — if the user has
 * previously added this product, we already have a {@code priceAtAddition}
 * snapshot recorded. That snapshot becomes our cached {@link ProductSummary}.
 *
 * <p>This implements the "Amazon model" of state-evolution caching:
 * <ul>
 *   <li>add-to-cart uses snapshot (possibly stale during outage)
 *   <li>checkout (L9 saga) re-validates against real-time product-service
 *   <li>no extra cache infrastructure — {@code cart_items} table is the cache
 * </ul>
 *
 * <p><b>UX boundary:</b> only UPSERT (existing cart row) degrades gracefully.
 * First-time add hits the fallback miss path and surfaces a 503 — the user
 * has no prior reference price, so we refuse rather than guess.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResilientProductClient {

    private final ProductClient productClient;
    private final CartItemRepository cartItemRepo;

    /**
     * Lookup product, protected by Resilience4j @Retry + @CircuitBreaker.
     *
     * <p>Aspect order (set in application.yml): CB outermost, Retry inside.
     * So a request flows: CB state check → if CLOSED, enter Retry → call
     * Feign up to 3 times with exponential backoff + jitter → on final
     * failure (or non-retryable exception), CB records ONE failure and
     * routes to {@link #findByIdFallback}.
     *
     * <p>This ordering matters: with the default Resilience4j order
     * (Retry outside CB), every retry attempt becomes a separate CB
     * statistic, inflating the failure rate by the retry count and
     * tripping CB prematurely.
     *
     * <p>{@code userId} is required so the fallback can look up the user's
     * cart for a cached priceAtAddition snapshot.
     */
    @CircuitBreaker(name = "productClient", fallbackMethod = "findByIdFallback")
    @Retry(name = "productClient")
    public ProductSummary findById(Long userId, Long productId) {
        log.debug("ResilientProductClient: forwarding findById(userId={}, productId={}) to Feign",
                userId, productId);
        return productClient.findById(productId);
    }

    /**
     * Fallback for {@link #findById}. Signature mirrors the main method plus a
     * trailing {@link Throwable} parameter — Resilience4j matches fallbacks by
     * this signature shape.
     *
     * <p>{@code @Transactional(readOnly = true)} because the fallback only
     * reads, and must NEVER itself fail in a way that propagates beyond the
     * caller's intended error surface.
     *
     * <p><b>小V TODO:</b> implement the fallback body. Requirements:
     * <ol>
     *   <li>Use {@link CartItemRepository#findById(Object)} with a
     *       {@link CartItemId}{@code (userId, productId)} composite key.
     *   <li>If present — log a WARN saying "fallback hit, using cached
     *       priceAtAddition" and return a {@link ProductSummary} reconstructed
     *       from the cart row. Other fields can be placeholders (e.g. name
     *       {@code "[cached]"}, sku {@code "CACHED-" + productId}, status
     *       {@code "CACHED"}) — CartService only consumes priceCents + currency.
     *   <li>If absent — log a WARN saying "fallback miss, no cached price",
     *       then throw {@link ResponseStatusException} with
     *       {@link HttpStatus#SERVICE_UNAVAILABLE} and message
     *       {@code "Product service unavailable; cannot add new item to cart"}.
     * </ol>
     *
     * <p>Tip: distinguish {@link CallNotPermittedException} (CB OPEN — fast
     * fail) from other exceptions in the log so we can tell during incident
     * review whether the CB was already tripped at the time of fallback.
     */
    @Transactional(readOnly = true)
    public ProductSummary findByIdFallback(Long userId, Long productId, Throwable cause) {
        // Semantic error — product genuinely does not exist. Don't degrade,
        // propagate the 404 distinction so CartService surfaces a proper
        // "Product not found" response instead of a misleading 503.
        if (cause instanceof FeignException.NotFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Product not found: " + productId);
        }

        // Infrastructure failure — CB OPEN, timeout, 5xx. Try the cache.
        CartItemId key = new CartItemId(userId, productId);
        Optional<CartItem> cached = cartItemRepo.findById(key);
        if (cached.isPresent()) {
            CartItem item = cached.get();
            log.warn("ResilientProductClient fallback HIT for userId={} productId={} cause={} "
                            + "→ returning cached priceAtAddition={} {}",
                    userId, productId, cause.getClass().getSimpleName(),
                    item.getPriceAtAddition(), item.getCurrency());
            return new ProductSummary(productId, "[cached]", "CACHED-" + productId,
                    item.getPriceAtAddition(), item.getCurrency(), "CACHED");
        } else {
            log.warn("ResilientProductClient fallback MISS for userId={} productId={} cause={} "
                            + "→ no cached snapshot, returning 503",
                    userId, productId, cause.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Product service unavailable; cannot add new item to cart");
        }
    }
}
