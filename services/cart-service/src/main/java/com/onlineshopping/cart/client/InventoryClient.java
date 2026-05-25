package com.onlineshopping.cart.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "inventory-service", url = "${app.inventory-service.base-url}")
public interface InventoryClient {

    @GetMapping("/inventory/{productId}")
    InventoryStock getStock(@PathVariable("productId") Long productId);
}
