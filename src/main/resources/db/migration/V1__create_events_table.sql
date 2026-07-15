-- The Gateway's local event store. Flyway owns this schema; Hibernate runs with ddl-auto: none
-- so there is exactly one source of truth for the DDL.

CREATE TABLE events (
    event_id        VARCHAR(128)             NOT NULL,
    account_id      VARCHAR(128)             NOT NULL,
    type            VARCHAR(16)              NOT NULL,
    amount          NUMERIC(19, 4)           NOT NULL,
    currency        VARCHAR(3)               NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata        TEXT,
    status          VARCHAR(16)              NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    -- This is the idempotency guarantee. Not an application-level check: the only thing that is
    -- atomic under concurrent duplicate submissions is the database constraint itself.
    CONSTRAINT pk_events PRIMARY KEY (event_id),

    CONSTRAINT chk_events_type   CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_events_status CHECK (status IN ('PENDING', 'APPLIED', 'FAILED')),
    CONSTRAINT chk_events_amount CHECK (amount > 0)
);

-- Serves GET /events?account={id}, which must return chronological order by event_timestamp.
CREATE INDEX idx_events_account_timestamp ON events (account_id, event_timestamp, event_id);

-- Serves the future outbox sweeper looking for PENDING/FAILED rows to re-drive.
CREATE INDEX idx_events_status ON events (status);
