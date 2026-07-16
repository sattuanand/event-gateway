package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;

/** Account Service's GET /accounts/{accountId} response. Field names match its AccountResponse. */
public record AccountBalance(
        String accountId,
        BigDecimal balance,
        String currency,
        Instant updatedAt) {
}
