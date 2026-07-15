package com.eventledger.gateway.domain;

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
}
