package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.EventRecord;
import com.eventledger.gateway.domain.EventRepository;
import com.eventledger.gateway.domain.EventStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every transactional boundary in the write path lives here, and NOWHERE else.
 *
 * <p>This class exists as a separate bean for one specific reason: a duplicate insert marks its
 * transaction rollback-only. If {@code EventService} caught the DataIntegrityViolationException
 * inside its own {@code @Transactional} method, the subsequent commit would blow up with
 * UnexpectedRollbackException. By letting the exception escape THIS bean's proxy, the transaction is
 * cleanly rolled back at the boundary and the caller — which is not transactional — is free to catch
 * it and read the existing row in a fresh transaction.
 *
 * <p>Just as important: no remote call is ever made inside one of these transactions. Holding a
 * database connection open across a network hop is how you turn a downstream slowdown into
 * connection-pool exhaustion.
 */
@Service
public class EventWriter {

    private final EventRepository repository;

    public EventWriter(EventRepository repository) {
        this.repository = repository;
    }

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException if eventId already exists.
     *         This is the idempotency check — the primary key, not an application-level lookup.
     */
    @Transactional
    public EventRecord insertPending(EventRecord record) {
        return repository.saveAndFlush(record);
    }

    @Transactional
    public int compareAndSetStatus(String eventId, EventStatus from, EventStatus to) {
        return repository.compareAndSetStatus(eventId, from, to, Instant.now());
    }

    @Transactional(readOnly = true)
    public Optional<EventRecord> find(String eventId) {
        return repository.findById(eventId);
    }

    @Transactional(readOnly = true)
    public List<EventRecord> findByAccount(String accountId) {
        return repository.findByAccountIdOrderByEventTimestampAscEventIdAsc(accountId);
    }
}
