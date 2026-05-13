package com.onlineshopping.product.controller;

import com.onlineshopping.product.dto.CreateProductRequest;
import com.onlineshopping.product.dto.ProductResponse;
import com.onlineshopping.product.entity.Product;
import com.onlineshopping.product.service.ProductService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products")
public class ProductController {
    @Resource
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest req) {
        Product saved = productService.create(req);
        return ProductResponse.from(saved);
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {
        return ProductResponse.from(productService.findById(id));
    }
}
