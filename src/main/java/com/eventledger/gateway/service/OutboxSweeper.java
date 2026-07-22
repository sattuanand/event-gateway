package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.EventRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Closes the "recovery is client-resubmit only" gap: redrives events left in FAILED — or orphaned
 * in PENDING after a crash mid-call — without waiting for a client to resubmit the same eventId.
 *
 * <p>This is the "Variant A" scheduler-only enhancement described in WIKI.md §6: it deliberately
 * introduces no new infrastructure (no broker, no CDC) and reuses {@link EventService},
 * {@link EventWriter} and {@code AccountServiceClient} completely unchanged. Every redrive attempt
 * goes through the exact same CAS guard and the exact same Retry/CircuitBreaker-wrapped call a
 * client-triggered resubmit uses (see {@link EventService#redriveIfEligible}).
 *
 * <p>Each sweep is two phases:
 * <ol>
 *   <li><b>Reap</b> — a single bulk, atomic {@code UPDATE} flips any PENDING row untouched for
 *       longer than {@code staleAfter} back to FAILED. This is what makes a row orphaned by a crash
 *       (the process died between "insert PENDING" and "the downstream call ever completing")
 *       eligible for redrive at all — nothing else would ever touch it again otherwise.</li>
 *   <li><b>Redrive</b> — up to {@code batchSize} FAILED rows (oldest first, excluding anything that
 *       has already exhausted {@code maxRedriveAttempts}) are each redriven one at a time. A
 *       per-row {@code try/catch} means one chronically-failing ("poison pill") event never stops
 *       the rest of the batch from being attempted, and never crashes the scheduled method itself
 *       (an uncaught exception from a {@code @Scheduled} method would silently kill that and every
 *       future run).</li>
 * </ol>
 *
 * <p>Disabled entirely via {@code event-gateway.outbox-sweeper.enabled=false} — the bean is not
 * created at all in that case ({@link ConditionalOnProperty}), so the schedule never registers.
 */
@Component
@ConditionalOnProperty(prefix = "event-gateway.outbox-sweeper", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxSweeper {

    private static final Logger log = LoggerFactory.getLogger(OutboxSweeper.class);

    private final EventWriter writer;
    private final EventService eventService;
    private final EventMetrics metrics;
    private final OutboxSweeperProperties properties;

    public OutboxSweeper(EventWriter writer, EventService eventService, EventMetrics metrics,
                         OutboxSweeperProperties properties) {
        this.writer = writer;
        this.eventService = eventService;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * The interval is a property placeholder, not {@link OutboxSweeperProperties#staleAfter()} or
     * similar — see that class's Javadoc for why. Default 5 minutes, overridable per environment
     * with no code change via {@code event-gateway.outbox-sweeper.interval-ms}.
     */
    @Scheduled(fixedDelayString = "${event-gateway.outbox-sweeper.interval-ms:300000}")
    public void sweep() {
        int reaped = writer.reapStalePending(Instant.now().minus(properties.staleAfter()));
        if (reaped > 0) {
            log.info("Outbox sweeper reaped {} orphaned PENDING event(s) to FAILED", reaped);
        }

        List<EventRecord> candidates = writer.findRedrivable(properties.maxRedriveAttempts(), properties.batchSize());
        if (candidates.isEmpty()) {
            return;
        }

        int redriven = 0;
        int stillFailed = 0;
        for (EventRecord record : candidates) {
            try {
                if (eventService.redriveIfEligible(record)) {
                    redriven++;
                } else {
                    // Lost the CAS race to a concurrent client resubmit — not a failure, just a no-op.
                    stillFailed++;
                }
            } catch (Exception e) {
                // Isolate one poison-pill event so it can't take down the rest of the batch or the
                // scheduled method itself (an uncaught exception here would stop future runs too).
                stillFailed++;
                log.warn("Outbox sweeper: eventId={} still failing (redriveCount={}): {}",
                        record.getEventId(), record.getRedriveCount() + 1, e.getMessage());
            }
        }

        log.info("Outbox sweep complete: {} candidate(s), {} redriven, {} still failed",
                candidates.size(), redriven, stillFailed);
        metrics.sweepCompleted(redriven, stillFailed);
    }
}
