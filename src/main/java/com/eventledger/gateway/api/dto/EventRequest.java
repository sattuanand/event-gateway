package com.eventledger.gateway.api.dto;

import com.eventledger.gateway.domain.EventType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventRequest(

        @NotBlank(message = "eventId is required")
        @Size(max = 128, message = "eventId must be at most 128 characters")
        String eventId,

        @NotBlank(message = "accountId is required")
        @Size(max = 128, message = "accountId must be at most 128 characters")
        String accountId,

        /* An unrecognised value here fails in Jackson, not Bean Validation — see
           GlobalExceptionHandler, which turns that into a readable 400 rather than a stack trace. */
        @NotNull(message = "type is required and must be CREDIT or DEBIT")
        EventType type,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter code, e.g. USD")
        String currency,

        @NotNull(message = "eventTimestamp is required (ISO-8601, e.g. 2026-05-15T14:02:11Z)")
        Instant eventTimestamp,

        Map<String, Object> metadata) {
}
