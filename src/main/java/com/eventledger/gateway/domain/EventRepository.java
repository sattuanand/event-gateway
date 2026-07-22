package com.eventledger.gateway.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EventRepository extends JpaRepository<EventRecord, String> {

    /**
     * Chronological by the UPSTREAM timestamp, not by arrival. eventId breaks ties so the ordering
     * is total and therefore stable across repeated calls.
     */
    List<EventRecord> findByAccountIdOrderByEventTimestampAscEventIdAsc(String accountId);

    /**
     * Atomic compare-and-set on status. Returns rows changed, so exactly one caller can win a
     * FAILED -> PENDING transition when two duplicate submissions race.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update EventRecord e
              set e.status = :toStatus,
                  e.updatedAt = :now
            where e.eventId = :eventId
              and e.status = :fromStatus
           """)
    int compareAndSetStatus(@Param("eventId") String eventId,
                            @Param("fromStatus") EventStatus fromStatus,
                            @Param("toStatus") EventStatus toStatus,
                            @Param("now") Instant now);

    /**
     * Bulk-reaps rows stuck in {@code fromStatus} (in practice: PENDING) that haven't been touched
     * since before {@code staleThreshold} — meaning the process most likely crashed mid-call, since
     * nothing ever flipped the row to a terminal status. Safe under concurrent sweepers: once a row's
     * status changes, the WHERE clause stops matching it, so two overlapping reaps can't both "win"
     * the same row the way {@link #compareAndSetStatus} guards a single-row CAS.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update EventRecord e
              set e.status = :toStatus,
                  e.updatedAt = :now
            where e.status = :fromStatus
              and e.updatedAt < :staleThreshold
           """)
    int reapStale(@Param("fromStatus") EventStatus fromStatus,
                  @Param("toStatus") EventStatus toStatus,
                  @Param("staleThreshold") Instant staleThreshold,
                  @Param("now") Instant now);

    /**
     * Candidates for the outbox sweeper: rows in {@code status} that haven't already exhausted the
     * configured redrive-attempt cap. Ordered oldest-first so the longest-stuck events are retried
     * first within a bounded batch.
     */
    @Query("""
           select e from EventRecord e
            where e.status = :status
              and e.redriveCount < :maxAttempts
            order by e.updatedAt asc
           """)
    List<EventRecord> findRedrivable(@Param("status") EventStatus status,
                                     @Param("maxAttempts") int maxAttempts,
                                     Pageable pageable);

    /**
     * Same contract as {@link #compareAndSetStatus}, but also increments {@code redriveCount} — used
     * specifically for the transition that precedes a redrive attempt, whether triggered by a client
     * resubmit or the scheduled sweeper, so both paths count toward the same poison-pill cap enforced
     * by {@link #findRedrivable}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update EventRecord e
              set e.status = :toStatus,
                  e.updatedAt = :now,
                  e.redriveCount = e.redriveCount + 1
            where e.eventId = :eventId
              and e.status = :fromStatus
           """)
    int compareAndSetStatusForRedrive(@Param("eventId") String eventId,
                                      @Param("fromStatus") EventStatus fromStatus,
                                      @Param("toStatus") EventStatus toStatus,
                                      @Param("now") Instant now);
}
