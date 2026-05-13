package com.onlineshopping.product.service;

import com.onlineshopping.product.dto.CreateCategoryRequest;
import com.onlineshopping.product.entity.Category;
import com.onlineshopping.product.repository.CategoryRepository;
import com.onlineshopping.product.snowflake.SnowflakeIdGenerator;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    @Resource
    private final CategoryRepository categoryRepository;

    @Resource
    private final SnowflakeIdGenerator snowflake;

    public CategoryService(CategoryRepository categoryRepository,
                    SnowflakeIdGenerator snowflake){
        this.categoryRepository = categoryRepository;
        this.snowflake = snowflake;
    }

    @Transactional
    public Category create(CreateCategoryRequest req){
        Category c = new Category();
        c.setId(snowflake.nextId());
        c.setName(req.name());
        c.setSlug(req.slug());
        c.setSortOrder(req.sortOrder());

        Category saved = categoryRepository.save(c);
        return saved;
    }
}
