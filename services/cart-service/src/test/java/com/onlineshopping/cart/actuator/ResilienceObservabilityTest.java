package com.onlineshopping.cart.actuator;

import com.onlineshopping.cart.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies Resilience4j observability is wired to Spring Boot Actuator
 * endpoints — critical for production. Without these, an OPEN circuit is
 * invisible to monitoring; you only learn about it from user complaints.
 *
 * <p>What this test guarantees:
 * <ul>
 *   <li>{@code /actuator/circuitbreakers} lists the {@code productClient}
 *       instance with its current state — scrapeable by Prometheus or
 *       readable manually during an incident.
 *   <li>{@code /actuator/health} reflects CB state so liveness probes can
 *       fail the pod when a critical downstream is unreachable.
 * </ul>
 *
 * <p>SecurityConfig already permits {@code /actuator/**} so these endpoints
 * are reachable without a JWT — same pattern as product/inventory.
 */
class ResilienceObservabilityTest extends AbstractIntegrationTest {

    @Test
    void actuator_circuitbreakers_exposesProductClientInstance() throws Exception {
        // The /actuator/circuitbreakers endpoint should list our configured
        // productClient instance and its current state (CLOSED on fresh boot,
        // before any traffic flows).
        mockMvc.perform(get("/actuator/circuitbreakers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitBreakers.productClient").exists())
                .andExpect(jsonPath("$.circuitBreakers.productClient.state").value("CLOSED"));
    }

    @Test
    void actuator_health_includesCircuitBreakerStatus() throws Exception {
        // With health.circuitbreakers.enabled=true, the health endpoint
        // aggregates CB state. A CLOSED CB contributes UP; OPEN would
        // contribute DOWN. Useful for liveness probes — though wire to
        // readiness only if the downstream is truly fatal to the service.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.circuitBreakers").exists());
    }
}
