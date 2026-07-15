package com.eventledger.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The Gateway's record of a received event. {@code eventId} is the client-supplied primary key —
 * that unique constraint is the ONLY atomic guard against concurrent duplicate submissions.
 *
 * <p>Implements {@link Persistable} so Spring Data issues a bare {@code persist()} rather than the
 * default {@code merge()} (SELECT-then-INSERT). Without this, concurrent duplicates would race on
 * the SELECT and we would be back to a check-then-insert bug.
 */
@Entity
@Table(name = "events")
public class EventRecord implements Persistable<String> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 128)
    private String eventId;

    @Column(name = "account_id", nullable = false, length = 128)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private EventType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** When the event originally occurred upstream. All ordering derives from this, never from arrival. */
    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    /** Raw JSON kept as text rather than jsonb, to keep the mapping boring and portable. */
    @Column(name = "metadata")
    private String metadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EventStatus status;

    /** When the Gateway received it — differs from eventTimestamp precisely because events arrive out of order. */
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected EventRecord() {
        // for JPA
    }

    public EventRecord(String eventId, String accountId, EventType type, BigDecimal amount,
                       String currency, Instant eventTimestamp, String metadata,
                       EventStatus status, Instant receivedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.status = status;
        this.receivedAt = receivedAt;
        this.updatedAt = receivedAt;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public EventType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public String getMetadata() {
        return metadata;
    }

    public EventStatus getStatus() {
        return status;
    }

    public void setStatus(EventStatus status) {
        this.status = status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
