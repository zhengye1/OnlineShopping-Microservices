package com.onlineshopping.product.controller;

import com.onlineshopping.product.dto.CreateProductRequest;
import com.onlineshopping.product.dto.ProductResponse;
import com.onlineshopping.product.entity.Product;
import com.onlineshopping.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
@Slf4j
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest req) {
        log.info("POST /products name={} sku={}", req.name(), req.sku());
        Product saved = productService.create(req);
        return ProductResponse.from(saved);
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {
        log.info("GET /products/{} requested", id);
        return ProductResponse.from(productService.findById(id));
    }
}
