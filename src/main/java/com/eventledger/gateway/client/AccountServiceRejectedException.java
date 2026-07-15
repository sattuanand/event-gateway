package com.eventledger.gateway.client;

/**
 * The Account Service answered with 4xx — it is healthy and it rejected us. That is a contract bug
 * on our side, not a downstream outage, so it must NOT count toward opening the circuit and must NOT
 * be reported to the client as 503. Maps to 502.
 */
public class AccountServiceRejectedException extends AccountServiceException {

    private final int downstreamStatus;

    public AccountServiceRejectedException(String message, int downstreamStatus, Throwable cause) {
        super(message, cause);
        this.downstreamStatus = downstreamStatus;
    }

    public int getDownstreamStatus() {
        return downstreamStatus;
    }
}
