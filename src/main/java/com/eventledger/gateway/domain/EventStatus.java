package com.eventledger.gateway.domain;

/**
 * Lifecycle of an event as tracked by the Gateway.
 *
 * <p>PENDING — durably stored locally, not yet confirmed applied by the Account Service.
 * <p>APPLIED — the Account Service has accepted the transaction. Terminal; resubmits are idempotent.
 * <p>FAILED  — the Account Service call did not succeed. Retryable: a resubmit of the same
 *              eventId re-attempts the downstream call.
 *
 * <p>This column exists from day one specifically so that the "async fallback" bonus (a
 * transactional-outbox replayer sweeping PENDING/FAILED rows) becomes a new class plus a scheduled
 * method — not a schema migration and a rewrite of the write path.
 */
public enum EventStatus {
    PENDING,
    APPLIED,
    FAILED
}
