package com.onlineshopping.product.entity;

public enum ProductStatus {
    DRAFT,           // 賣家草稿，未發佈
    ACTIVE,          // 上架中
    OUT_OF_STOCK,    // 售罄但仲想 keep visibility
    ARCHIVED         // 完全下架（soft state 唔同 deleted_at）
}
