package com.onlineshopping.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * L7: 4th microservice — aggregates user + product + inventory state.
 *
 * <p>{@code @EnableFeignClients} scans for {@code @FeignClient} interfaces
 * (ProductClient, InventoryClient) and generates HTTP-calling proxies.
 */
@SpringBootApplication(scanBasePackages = {
        "com.onlineshopping.cart",
        "com.onlineshopping.common.web"
})
@EnableFeignClients
public class CartServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }
}
