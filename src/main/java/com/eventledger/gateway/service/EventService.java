package com.eventledger.gateway.service;

import com.eventledger.gateway.api.dto.EventRequest;
import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceException;
import com.eventledger.gateway.client.AccountServiceRejectedException;
import com.eventledger.gateway.client.TransactionRequest;
import com.eventledger.gateway.domain.EventRecord;
import com.eventledger.gateway.domain.EventStatus;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the write path. Deliberately NOT {@code @Transactional} — see {@link EventWriter}.
 *
 * <p>The sequence is: durably store as PENDING, then call the Account Service, then record the
 * result. Storing first means the acknowledgement we send upstream is never a lie: by the time the
 * client sees any response at all, the event is on disk. If the downstream call then fails we return
 * 503 and the row sits in FAILED — recoverable either by an upstream resubmit or, later, by an
 * outbox sweeper. The alternative (call first, store on success) has a window where we have applied
 * a real balance change and then crash without any local record of it.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventWriter writer;
    private final AccountServiceClient accountServiceClient;
    private final EventMapper mapper;
    private final EventMetrics metrics;

    public EventService(EventWriter writer, AccountServiceClient accountServiceClient,
                        EventMapper mapper, EventMetrics metrics) {
        this.writer = writer;
        this.accountServiceClient = accountServiceClient;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public SubmitOutcome submit(EventRequest request) {
        EventRecord record = mapper.toNewRecord(request);

        try {
            writer.insertPending(record);
        } catch (DataIntegrityViolationException duplicate) {
            // The primary key rejected us. This is the ONLY reliable duplicate detection: a
            // preceding existsById() check would have a race window between the check and the insert.
            log.info("Duplicate submission for eventId={}", record.getEventId());
            return handleExisting(record.getEventId());
        }

        metrics.received(record.getType());
        log.info("Accepted eventId={} accountId={} type={} amount={} eventTimestamp={}",
                record.getEventId(), record.getAccountId(), record.getType(),
                record.getAmount(), record.getEventTimestamp());

        applyDownstream(record);
        return new SubmitOutcome(record, false);
    }

    /**
     * A resubmit of an eventId we already hold. APPLIED is a settled duplicate and we simply return
     * the original. FAILED means our earlier downstream call did not land, so this resubmit is a free
     * chance to re-drive it — exactly what a well-behaved upstream retrying our 503 is for.
     */
    private SubmitOutcome handleExisting(String eventId) {
        EventRecord existing = writer.find(eventId)
                .orElseThrow(() -> new IllegalStateException(
                        "Insert of eventId=" + eventId + " conflicted but the row is not readable"));

        if (existing.getStatus() == EventStatus.FAILED) {
            // Only the caller that wins this compare-and-set re-drives the downstream call; any
            // concurrent duplicate just reads back the current state instead of double-applying.
            if (writer.compareAndSetStatus(eventId, EventStatus.FAILED, EventStatus.PENDING) == 1) {
                existing.setStatus(EventStatus.PENDING);
                log.info("Re-driving previously FAILED eventId={}", eventId);
                applyDownstream(existing);
                return new SubmitOutcome(existing, true);
            }
        }

        metrics.duplicate(existing.getStatus().name());
        return new SubmitOutcome(existing, true);
    }

    private void applyDownstream(EventRecord record) {
        Timer.Sample sample = metrics.startApplyTimer();
        try {
            accountServiceClient.applyTransaction(record.getAccountId(), TransactionRequest.from(record));
            writer.compareAndSetStatus(record.getEventId(), EventStatus.PENDING, EventStatus.APPLIED);
            record.setStatus(EventStatus.APPLIED);
            metrics.applied(record.getType());
            metrics.stopApplyTimer(sample, "applied");
        } catch (AccountServiceException e) {
            writer.compareAndSetStatus(record.getEventId(), EventStatus.PENDING, EventStatus.FAILED);
            record.setStatus(EventStatus.FAILED);
            String reason = (e instanceof AccountServiceRejectedException) ? "rejected" : "unavailable";
            metrics.downstreamFailed(reason);
            metrics.stopApplyTimer(sample, reason);
            log.warn("eventId={} stored but left in FAILED: {}", record.getEventId(), e.getMessage());
            throw e; // surfaces as 503 (unavailable) or 502 (rejected) via GlobalExceptionHandler
        }
    }

    public EventRecord get(String eventId) {
        return writer.find(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    }

    /** Reads never touch the Account Service — this is what keeps them working during an outage. */
    public List<EventRecord> listByAccount(String accountId) {
        return writer.findByAccount(accountId);
    }
}
