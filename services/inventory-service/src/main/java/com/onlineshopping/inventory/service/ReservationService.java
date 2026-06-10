package com.onlineshopping.inventory.service;

import com.onlineshopping.inventory.entity.InventoryReservation;
import com.onlineshopping.inventory.entity.ReservationStatus;
import com.onlineshopping.inventory.repository.InventoryRepository;
import com.onlineshopping.inventory.repository.InventoryReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * L9 reservation lifecycle. Bridges the saga events to the stock
 * accounting on {@code inventories.reserved_stock}.
 *
 * <p>Three public operations + a TTL reaper:
 *
 * <ul>
 *   <li>{@link #reserve(Long, Long, Long, int)} — atomic INSERT during saga
 *       step 1 (OrderCreated). Throws 409 if stock insufficient.
 *   <li>{@link #commitForOrder(Long)} — finalize on saga payment success.
 *       Decrements both stock_quantity and reserved_stock.
 *   <li>{@link #releaseForOrder(Long, String)} — saga compensation on
 *       payment fail. Frees reserved_stock back to available.
 *   <li>{@link #releaseExpired()} — scheduled TTL reaper. Frees ACTIVE
 *       reservations past expires_at.
 * </ul>
 *
 * <p>The state machine is enforced via the affected-rows check pattern:
 * a state transition is only valid if the row is still in the expected
 * source state. Two racing handlers (e.g. payment-success vs TTL reaper)
 * cannot both succeed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final InventoryRepository inventoryRepo;
    private final InventoryReservationRepository reservationRepo;

    @Value("${app.reservation.ttl-minutes:15}")
    private long ttlMinutes;

    /**
     * Atomic reserve — single conditional UPDATE eliminates check-then-act
     * race; INSERT records the reservation for compensation lookup later.
     *
     * @throws ResponseStatusException 409 if stock insufficient
     */
    @Transactional
    public InventoryReservation reserve(Long productId, Long userId, Long orderId, int quantity) {
        int affected = inventoryRepo.tryReserve(productId, quantity);
        if (affected == 0) {
            log.warn("Reservation FAILED — insufficient stock: productId={} qty={} orderId={}",
                    productId, quantity, orderId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Insufficient stock for product " + productId);
        }

        InventoryReservation res = InventoryReservation.builder()
                .productId(productId)
                .userId(userId)
                .orderId(orderId)
                .quantity(quantity)
                .status(ReservationStatus.ACTIVE)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(ttlMinutes)))
                .build();
        InventoryReservation saved = reservationRepo.save(res);
        log.info("Reservation RESERVED — id={} productId={} qty={} orderId={} expiresAt={}",
                saved.getId(), productId, quantity, orderId, saved.getExpiresAt());
        return saved;
    }

    /**
     * Finalize all ACTIVE reservations for an order on payment success.
     * State machine guard: only ACTIVE → COMMITTED transitions count.
     */
    @Transactional
    public void commitForOrder(Long orderId) {
        List<InventoryReservation> active =
                reservationRepo.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE);
        if (active.isEmpty()) {
            log.warn("commitForOrder no-op — no ACTIVE reservations for orderId={}", orderId);
            return;
        }
        for (InventoryReservation r : active) {
            int affected = inventoryRepo.commitReserved(r.getProductId(), r.getQuantity());
            if (affected == 0) {
                log.error("INVARIANT VIOLATION — commitReserved affected 0 rows for productId={} qty={}",
                        r.getProductId(), r.getQuantity());
                throw new IllegalStateException("Reservation commit accounting broke for " + r.getId());
            }
            r.setStatus(ReservationStatus.COMMITTED);
            reservationRepo.save(r);                  // @Version catches stale-write races
            log.info("Reservation COMMITTED — id={} productId={} qty={} orderId={}",
                    r.getId(), r.getProductId(), r.getQuantity(), orderId);
        }
    }

    /**
     * Compensation — release reservations for an order.
     * State machine guard: only ACTIVE → RELEASED transitions count.
     */
    @Transactional
    public void releaseForOrder(Long orderId, String reason) {
        List<InventoryReservation> active =
                reservationRepo.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE);
        if (active.isEmpty()) {
            log.warn("releaseForOrder no-op — no ACTIVE reservations for orderId={}", orderId);
            return;
        }
        for (InventoryReservation r : active) {
            int affected = inventoryRepo.releaseReserved(r.getProductId(), r.getQuantity());
            if (affected == 0) {
                log.error("INVARIANT VIOLATION — releaseReserved affected 0 rows for productId={} qty={}",
                        r.getProductId(), r.getQuantity());
                throw new IllegalStateException("Reservation release accounting broke for " + r.getId());
            }
            r.setStatus(ReservationStatus.RELEASED);
            r.setReleaseReason(reason);
            reservationRepo.save(r);
            log.info("Reservation RELEASED — id={} productId={} qty={} orderId={} reason={}",
                    r.getId(), r.getProductId(), r.getQuantity(), orderId, reason);
        }
    }

    /**
     * TTL reaper — scheduled every minute. Frees ACTIVE reservations whose
     * expires_at has passed. Each release happens in its own try/catch so
     * one failing reservation doesn't poison the whole sweep.
     */
    @Scheduled(fixedDelayString = "${app.reservation.reaper-interval-ms:60000}")
    public void releaseExpired() {
        List<InventoryReservation> expired = reservationRepo.findExpiredActive(Instant.now());
        if (expired.isEmpty()) return;
        log.info("TTL reaper sweeping {} expired reservations", expired.size());
        for (InventoryReservation r : expired) {
            try {
                releaseSingleExpired(r);
            } catch (Exception e) {
                log.error("TTL reaper failed for reservation id={} — continuing sweep",
                        r.getId(), e);
            }
        }
    }

    /**
     * Single TTL release in its own transaction so a partial sweep failure
     * doesn't roll back already-released reservations.
     */
    @Transactional
    protected void releaseSingleExpired(InventoryReservation r) {
        // Re-read inside transaction to dodge the race where payment-success
        // committed this reservation between the reaper query and the release.
        InventoryReservation fresh = reservationRepo.findById(r.getId()).orElse(null);
        if (fresh == null || fresh.getStatus() != ReservationStatus.ACTIVE) {
            log.debug("TTL reaper skipping id={} — already in state {}",
                    r.getId(), fresh == null ? "DELETED" : fresh.getStatus());
            return;
        }
        int affected = inventoryRepo.releaseReserved(fresh.getProductId(), fresh.getQuantity());
        if (affected == 0) {
            log.error("TTL reaper accounting failure for id={} productId={} qty={}",
                    fresh.getId(), fresh.getProductId(), fresh.getQuantity());
            return;
        }
        fresh.setStatus(ReservationStatus.RELEASED);
        fresh.setReleaseReason("TTL_EXPIRED");
        reservationRepo.save(fresh);
        log.info("Reservation TTL_EXPIRED — id={} productId={} qty={}",
                fresh.getId(), fresh.getProductId(), fresh.getQuantity());
    }
}
