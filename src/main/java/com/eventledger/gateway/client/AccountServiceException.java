package com.eventledger.gateway.client;

/** Base type for anything that went wrong on the Gateway -> Account Service hop. */
public abstract class AccountServiceException extends RuntimeException {

    protected AccountServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
