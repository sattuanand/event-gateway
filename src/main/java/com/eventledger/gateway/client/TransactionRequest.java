package com.eventledger.gateway.client;

import com.eventledger.gateway.domain.EventRecord;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Gateway -> Account Service contract for POST /accounts/{accountId}/transactions.
 *
 * <p>eventId is carried across deliberately: the Account Service uses it as ITS idempotency key.
 * That second layer of defence is what makes it safe for the Gateway to re-drive a FAILED event
 * without knowing whether the original call actually landed before the connection broke.
 */
public record TransactionRequest(
        String eventId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp) {

    public static TransactionRequest from(EventRecord record) {
        return new TransactionRequest(
                record.getEventId(),
                record.getType().name(),
                record.getAmount(),
                record.getCurrency(),
                record.getEventTimestamp());
    }
}
