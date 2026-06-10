package com.onlineshopping.inventory.repository;

import com.onlineshopping.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * Atomic conditional reservation — bumps {@code reserved_stock} by qty
     * if and only if available stock ({@code stock_quantity - reserved_stock})
     * is sufficient.
     *
     * <p><b>Why a single UPDATE instead of read-check-update:</b> the naive
     * pattern (read inventory, check stock, save with new reserved_stock)
     * has a race window between read and save. Two concurrent reservers
     * can both pass the check and both end up reserving the same units,
     * leading to oversell.
     *
     * <p>MySQL row lock acquired during this UPDATE serializes competing
     * attempts on the same product_id. The {@code WHERE} clause's
     * arithmetic check fires while the row is locked, so check + update
     * are atomic.
     *
     * <p>Returns the number of rows affected:
     * <ul>
     *   <li>1 — reservation succeeded
     *   <li>0 — stock insufficient (caller throws InsufficientStock)
     * </ul>
     */
    @Modifying
    @Query(value = """
            UPDATE inventories
               SET reserved_stock = reserved_stock + :qty,
                   version = version + 1
             WHERE product_id = :productId
               AND stock_quantity - reserved_stock >= :qty
            """, nativeQuery = true)
    int tryReserve(@Param("productId") Long productId, @Param("qty") int qty);

    /**
     * Saga compensation / TTL release — decrement reserved_stock without
     * touching stock_quantity. Stock returns to "available" bucket.
     */
    @Modifying
    @Query(value = """
            UPDATE inventories
               SET reserved_stock = reserved_stock - :qty,
                   version = version + 1
             WHERE product_id = :productId
               AND reserved_stock >= :qty
            """, nativeQuery = true)
    int releaseReserved(@Param("productId") Long productId, @Param("qty") int qty);

    /**
     * Payment-success commit — decrement BOTH stock_quantity and
     * reserved_stock by the reserved quantity. Stock has been sold; the
     * reservation is consumed.
     */
    @Modifying
    @Query(value = """
            UPDATE inventories
               SET stock_quantity = stock_quantity - :qty,
                   reserved_stock = reserved_stock - :qty,
                   version = version + 1
             WHERE product_id = :productId
               AND reserved_stock >= :qty
            """, nativeQuery = true)
    int commitReserved(@Param("productId") Long productId, @Param("qty") int qty);
}
