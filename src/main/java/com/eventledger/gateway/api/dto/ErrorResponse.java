package com.eventledger.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** The traceId is included so a caller can quote it straight back to us in a support ticket. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<String> details,
        String traceId) {
}
