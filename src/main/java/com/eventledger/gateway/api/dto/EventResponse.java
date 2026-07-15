package com.eventledger.gateway.api.dto;

import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.domain.EventType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,
        EventStatus status,
        Instant receivedAt) {
}
