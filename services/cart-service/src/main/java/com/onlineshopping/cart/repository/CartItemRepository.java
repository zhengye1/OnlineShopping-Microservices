package com.onlineshopping.cart.repository;

import com.onlineshopping.cart.entity.CartItem;
import com.onlineshopping.cart.entity.CartItemId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CartItemRepository extends JpaRepository<CartItem, CartItemId> {

    /** Return all items currently in the user's cart. */
    List<CartItem> findByUserId(Long userId);
}
