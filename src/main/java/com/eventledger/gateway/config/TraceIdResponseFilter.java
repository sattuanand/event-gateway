package com.eventledger.gateway.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Echoes the current trace ID back on the response as {@code X-Trace-Id}.
 *
 * <p>Not required by the spec, but it means an operator (or a test) can take the trace ID straight
 * off a client response and grep it across both services' logs. Ordered just after
 * {@code ServerHttpObservationFilter} so a span already exists, and the header is set before the
 * chain runs so it lands before the response is committed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdResponseFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final Tracer tracer;

    public TraceIdResponseFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null) {
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }
        filterChain.doFilter(request, response);
    }
}
