package com.eventledger.gateway.client;

/**
 * The Account Service could not be reached, timed out, returned 5xx, or the circuit breaker is open.
 * Maps to 503 — the client should retry later, and idempotency makes that retry safe.
 */
public class AccountServiceUnavailableException extends AccountServiceException {

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
