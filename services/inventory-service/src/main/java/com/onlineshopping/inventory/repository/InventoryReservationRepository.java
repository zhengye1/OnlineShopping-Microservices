package com.onlineshopping.inventory.repository;

import com.onlineshopping.inventory.entity.InventoryReservation;
import com.onlineshopping.inventory.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {

    /** Saga compensation lookup — find ACTIVE reservations for a failing order. */
    List<InventoryReservation> findByOrderIdAndStatus(Long orderId, ReservationStatus status);

    /** TTL reaper query — page-limited to avoid loading huge result sets. */
    @Query("""
           SELECT r FROM InventoryReservation r
           WHERE r.status = com.onlineshopping.inventory.entity.ReservationStatus.ACTIVE
             AND r.expiresAt < :now
           ORDER BY r.expiresAt ASC
           """)
    List<InventoryReservation> findExpiredActive(@Param("now") Instant now);

    /** Lookup by id when used for forensics / API surface. */
    Optional<InventoryReservation> findFirstByOrderIdAndStatus(Long orderId, ReservationStatus status);
}
