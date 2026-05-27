package com.onlineshopping.cart.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * L7 Phase 3: forwards the inbound user's {@code Authorization: Bearer ...}
 * header to every outbound Feign call.
 *
 * <p>Industry pattern <em>"forward client token"</em> (Option 1 of L7 Phase 3
 * design): downstream services see the original user identity for row-level
 * AuthZ, no separate service-account token needed, no extra round-trip.
 *
 * <p>Implementation note: relies on Spring servlet's {@link RequestContextHolder}
 * (ThreadLocal-backed). Safe in the synchronous servlet model used by all four
 * services. If we ever migrate to WebFlux, this approach breaks - reactive
 * propagation needs Reactor Context, not ThreadLocal.
 *
 * <p>Discovered as a {@code @Component}; auto-registered with every Feign
 * client via Spring Cloud's auto-config.
 */
@Component
@Slf4j
public class FeignAuthForwardInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attrs == null) {
            // Background thread (scheduled task, Kafka consumer) - no inbound request
            // to forward from. Caller must supply auth out-of-band (M2M token, future work).
            log.debug("No servlet request context; skipping Authorization forwarding");
            return;
        }

        HttpServletRequest request = attrs.getRequest();
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && !authHeader.isBlank()) {
            template.header(HttpHeaders.AUTHORIZATION, authHeader);
        }
    }
}
