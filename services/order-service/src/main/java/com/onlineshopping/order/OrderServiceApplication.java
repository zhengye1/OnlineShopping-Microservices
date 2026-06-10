package com.onlineshopping.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * L9 — Order Service (5th microservice).
 *
 * <p>Owns the {@code orders} aggregate + saga state machine for distributed
 * checkout. Choreography pattern — order-service publishes
 * {@code OrderCreatedEvent} and consumes downstream
 * {@code StockReservedEvent} / {@code PaymentChargedEvent} +
 * {@code StockReservationFailedEvent} / {@code PaymentFailedEvent} to drive
 * the Order's state machine forward (or trigger compensation).
 *
 * <p>{@code scanBasePackages} includes {@code com.onlineshopping.common.web}
 * so {@code CorrelationIdFilter} from the shared module wires automatically
 * — same pattern as the other 4 services.
 */
@SpringBootApplication(scanBasePackages = {
        "com.onlineshopping.order",
        "com.onlineshopping.common.web"
})
@EnableKafka
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
