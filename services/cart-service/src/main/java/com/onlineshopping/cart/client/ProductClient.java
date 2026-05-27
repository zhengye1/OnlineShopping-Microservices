package com.onlineshopping.cart.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative HTTP client to product-service. Spring Cloud OpenFeign generates
 * the proxy implementation at runtime — no need to write boilerplate HTTP code.
 *
 * <p>{@code FeignAuthForwardInterceptor} automatically attaches the inbound
 * user's JWT to every call - downstream product-service verifies it via JWKS.
 */
@FeignClient(name = "product-service", url = "${app.product-service.base-url}")
public interface ProductClient {

    @GetMapping("/products/{id}")
    ProductSummary findById(@PathVariable("id") Long id);
}
