package com.onlineshopping.product.service;

import com.onlineshopping.product.dto.CreateCategoryRequest;
import com.onlineshopping.product.entity.Category;
import com.onlineshopping.product.repository.CategoryRepository;
import com.onlineshopping.product.snowflake.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    private final SnowflakeIdGenerator snowflake;

    public CategoryService(CategoryRepository categoryRepository,
                    SnowflakeIdGenerator snowflake){
        this.categoryRepository = categoryRepository;
        this.snowflake = snowflake;
    }

    @Transactional
    public Category create(CreateCategoryRequest req){
        Category c = Category.builder()
                .id(snowflake.nextId())
                .name(req.name())
                .slug(req.slug())
                .sortOrder(req.sortOrder())
                .build();
        return categoryRepository.save(c);
    }
}
