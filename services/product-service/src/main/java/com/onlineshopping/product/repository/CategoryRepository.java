package com.onlineshopping.product.repository;

import com.onlineshopping.product.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findBySlugAndDeletedAtIsNull(String slug);
    List<Category> findByParentIdIsNullAndDeletedAtIsNull();   // root categories
}
