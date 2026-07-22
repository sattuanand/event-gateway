package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.EventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Custom application metrics (requirement 4). Scraped at /actuator/prometheus.
 *
 * <p>These are deliberately business-level counters rather than another HTTP request counter —
 * Spring Boot already gives us http.server.requests for free. The interesting question in this
 * system is not "how many requests" but "how many of them were duplicates, and how many events are
 * sitting in FAILED because the downstream was down".
 */
@Component
public class EventMetrics {

    private static final String RECEIVED = "events.received";
    private static final String DUPLICATE = "events.duplicate";
    private static final String APPLIED = "events.applied";
    private static final String REJECTED = "events.rejected";
    private static final String DOWNSTREAM_FAILED = "events.downstream.failed";
    private static final String APPLY_LATENCY = "events.apply.latency";
    private static final String SWEEP_REDRIVEN = "events.sweeper.redriven";
    private static final String SWEEP_STILL_FAILED = "events.sweeper.still_failed";

    private final MeterRegistry registry;

    public EventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void received(EventType type) {
        registry.counter(RECEIVED, "type", type.name()).increment();
    }

    public void duplicate(String status) {
        registry.counter(DUPLICATE, "existing_status", status).increment();
    }

    public void applied(EventType type) {
        registry.counter(APPLIED, "type", type.name()).increment();
    }

    public void rejected(String reason) {
        registry.counter(REJECTED, "reason", reason).increment();
    }

    public void downstreamFailed(String reason) {
        registry.counter(DOWNSTREAM_FAILED, "reason", reason).increment();
    }

    public Timer.Sample startApplyTimer() {
        return Timer.start(registry);
    }

    public void stopApplyTimer(Timer.Sample sample, String outcome) {
        sample.stop(registry.timer(APPLY_LATENCY, "outcome", outcome));
    }

    /** One outbox sweep's outcome — how many events it successfully redrove vs. how many are still stuck. */
    public void sweepCompleted(int redriven, int stillFailed) {
        registry.counter(SWEEP_REDRIVEN).increment(redriven);
        registry.counter(SWEEP_STILL_FAILED).increment(stillFailed);
    }
}
