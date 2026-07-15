package com.eventledger.gateway.api;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The spec asks for GET /health specifically, so this is a plain controller rather than Actuator's
 * /actuator/health (which is still available, and still exposes the breaker's own health indicator).
 *
 * <p>Note what does NOT make this unhealthy: an open circuit breaker. The Account Service being down
 * is a degradation, not a Gateway failure — reads still work, writes still persist. Reporting DOWN
 * here would get the Gateway killed by an orchestrator's liveness probe for a fault in someone
 * else's service, which is precisely the cascade the breaker exists to prevent.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public HealthController(DataSource dataSource, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.dataSource = dataSource;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseUp = isDatabaseReachable();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "event-gateway");
        body.put("status", databaseUp ? "UP" : "DOWN");
        body.put("timestamp", Instant.now());

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("database", databaseUp ? "UP" : "DOWN");

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("accountService");
        Map<String, Object> breakerInfo = new LinkedHashMap<>();
        breakerInfo.put("state", breaker.getState().name());
        breakerInfo.put("failureRate", breaker.getMetrics().getFailureRate());
        breakerInfo.put("bufferedCalls", breaker.getMetrics().getNumberOfBufferedCalls());
        checks.put("accountServiceCircuitBreaker", breakerInfo);

        body.put("checks", checks);

        return ResponseEntity.status(databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            log.error("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}
