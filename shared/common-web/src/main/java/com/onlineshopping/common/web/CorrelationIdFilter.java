package com.onlineshopping.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * L7 Phase 4: stamps every inbound request with a correlation ID and propagates
 * it through MDC + response header. Combined with logback pattern
 * {@code %X{correlationId}}, this gives every log line across all services a
 * shared identifier for the same client request — grep one ID, see the entire
 * cross-service trace.
 *
 * <p>{@code @Order(Ordered.HIGHEST_PRECEDENCE)} ensures the filter runs before
 * Spring Security, DispatcherServlet, and any business filter — so all
 * downstream log lines benefit from the MDC value.
 *
 * <p>{@code MDC.remove} in {@code finally} is mandatory: Tomcat thread pools
 * reuse worker threads, and a stale MDC entry would leak the previous request's
 * correlation ID into the next request's log lines.
 *
 * <p>Header convention: {@code X-Correlation-ID} (most common across REST APIs).
 * Stripe / Heroku use {@code X-Request-ID}; W3C Trace Context's {@code traceparent}
 * is the future direction (OpenTelemetry) but heavier to wire — deferred.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        try {
            MDC.put(MDC_KEY, correlationId);
            response.setHeader(HEADER, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
