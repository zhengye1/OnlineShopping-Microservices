package com.onlineshopping.product.controller;

import com.onlineshopping.product.dto.CategoryResponse;
import com.onlineshopping.product.dto.CreateCategoryRequest;
import com.onlineshopping.product.dto.CreateProductRequest;
import com.onlineshopping.product.dto.ProductResponse;
import com.onlineshopping.product.entity.Category;
import com.onlineshopping.product.entity.Product;
import com.onlineshopping.product.service.CategoryService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/categories")
public class CategoryController {
    @Resource
    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService){
        this.categoryService = categoryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest req) {
        Category saved = categoryService.create(req);
        return CategoryResponse.from(saved);
    }
}
