package com.onlineshopping.product.repository;

import com.onlineshopping.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySkuAndDeletedAtIsNull(String sku);

    @Query("SELECT p FROM Product p WHERE p.categoryId = :catId AND p.deletedAt IS NULL")
    Page<Product> findActiveByCategory(@Param("catId") Long catId, Pageable pageable);
}
