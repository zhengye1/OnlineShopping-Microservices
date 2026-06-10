package com.onlineshopping.order.service;

import com.onlineshopping.order.dto.CreateOrderRequest;
import com.onlineshopping.order.entity.Order;
import com.onlineshopping.order.entity.OrderItem;
import com.onlineshopping.order.entity.OrderStatus;
import com.onlineshopping.order.event.OrderCreatedEvent;
import com.onlineshopping.order.repository.OrderRepository;
import com.onlineshopping.order.snowflake.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * L9 OrderService — handles POST /orders and saga state transitions.
 *
 * <p>{@link #create} is the saga's first step: persist Order in
 * PENDING_INVENTORY state then publish OrderCreatedEvent. Idempotency-Key
 * header support prevents duplicate orders on retry — the second call with
 * the same key returns the original order.
 *
 * <p>{@link #handleStockReserved}, {@link #handleStockReservationFailed},
 * {@link #handlePaymentCharged}, and {@link #handlePaymentFailed} are state
 * transitions driven by saga events. Each uses the order's current
 * {@code status} as the precondition guard — duplicate / out-of-order
 * events are detected by status mismatch and ignored.
 *
 * <p><b>Why no processed_events table?</b> Order status is the natural
 * idempotency key for saga events. Two StockReservedEvent for the same
 * order: the second won't transition because status is no longer
 * PENDING_INVENTORY. The natural state machine guard is cheaper than an
 * extra dedup table.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepo;
    private final SnowflakeIdGenerator snowflake;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topic.order-events}")
    private String orderEventsTopic;

    @Transactional
    public Order create(Long userId, CreateOrderRequest req, String idempotencyKey) {
        // Idempotency — short-circuit duplicate POST.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = orderRepo.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency HIT for key={} → returning existing order id={}",
                        idempotencyKey, existing.get().getId());
                return existing.get();
            }
        }

        long totalCents = req.items().stream()
                .mapToLong(i -> i.priceAtOrderCents() * i.quantity())
                .sum();

        Order order = Order.builder()
                .id(snowflake.nextId())
                .userId(userId)
                .status(OrderStatus.PENDING_INVENTORY)
                .totalAmountCents(totalCents)
                .currency(req.currency())
                .idempotencyKey(idempotencyKey)
                .build();
        // Wire items 雙向 reference for cascade persist.
        req.items().forEach(i -> {
            OrderItem item = OrderItem.builder()
                    .order(order)
                    .productId(i.productId())
                    .quantity(i.quantity())
                    .priceAtOrderCents(i.priceAtOrderCents())
                    .currency(req.currency())
                    .build();
            order.getItems().add(item);
        });

        Order saved = orderRepo.save(order);
        log.info("Order CREATED — id={} userId={} totalCents={} currency={}",
                saved.getId(), userId, totalCents, req.currency());

        // Publish saga step 1 trigger.
        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID(),
                Instant.now(),
                saved.getId(),
                saved.getUserId(),
                saved.getItems().stream()
                        .map(i -> new OrderCreatedEvent.Item(i.getProductId(),
                                i.getQuantity(), i.getPriceAtOrderCents()))
                        .toList(),
                saved.getTotalAmountCents(),
                saved.getCurrency()
        );
        kafkaTemplate.send(orderEventsTopic, String.valueOf(saved.getId()), event);
        log.info("Published OrderCreatedEvent → topic={} orderId={}", orderEventsTopic, saved.getId());

        return saved;
    }

    @Transactional
    public void handleStockReserved(Long orderId) {
        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("handleStockReserved — order not found orderId={}", orderId);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_INVENTORY) {
            log.info("handleStockReserved IGNORED — orderId={} already in state {}",
                    orderId, order.getStatus());
            return;
        }
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        orderRepo.save(order);
        log.info("Order PENDING_INVENTORY → PENDING_PAYMENT — orderId={}", orderId);
    }

    @Transactional
    public void handleStockReservationFailed(Long orderId, String reason) {
        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("handleStockReservationFailed — order not found orderId={}", orderId);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_INVENTORY) {
            log.info("handleStockReservationFailed IGNORED — orderId={} already in state {}",
                    orderId, order.getStatus());
            return;
        }
        order.cancel("STOCK_UNAVAILABLE: " + reason);
        orderRepo.save(order);
        log.info("Order CANCELLED (stock unavailable) — orderId={} reason={}", orderId, reason);
    }

    @Transactional
    public void handlePaymentCharged(Long orderId) {
        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("handlePaymentCharged — order not found orderId={}", orderId);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            log.info("handlePaymentCharged IGNORED — orderId={} already in state {}",
                    orderId, order.getStatus());
            return;
        }
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepo.save(order);
        log.info("Order PENDING_PAYMENT → CONFIRMED — orderId={}", orderId);
    }

    @Transactional
    public void handlePaymentFailed(Long orderId, String reason) {
        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("handlePaymentFailed — order not found orderId={}", orderId);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            log.info("handlePaymentFailed IGNORED — orderId={} already in state {}",
                    orderId, order.getStatus());
            return;
        }
        order.cancel("PAYMENT_FAILED: " + reason);
        orderRepo.save(order);
        log.info("Order PENDING_PAYMENT → CANCELLED (payment failed) — orderId={} reason={}",
                orderId, reason);
        // Compensation publishing happens in Phase 6.
    }
}
