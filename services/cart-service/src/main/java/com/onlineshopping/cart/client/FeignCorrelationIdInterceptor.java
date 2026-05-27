package com.onlineshopping.cart.client;

import com.onlineshopping.common.web.CorrelationIdFilter;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Forwards the inbound request's correlation ID to outbound Feign calls.
 * Mirror pattern of FeignAuthForwardInterceptor (JWT propagation, sub-phase 3a).
 *
 * <p>Reads from MDC (set by CorrelationIdFilter) — works because Feign call
 * runs on the same servlet thread that handled the inbound HTTP request, so
 * the ThreadLocal MDC is the original request's.
 */
@Component
public class FeignCorrelationIdInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            template.header(CorrelationIdFilter.HEADER, correlationId);
        }
    }
}
