package com.eventledger.gateway.client;

/**
 * Account Service answered 404 for a balance lookup — there is genuinely no such account, not a
 * failure on our side. Deliberately NOT an {@link AccountServiceException}: that hierarchy is for
 * things that went WRONG on the hop (unreachable, rejected); this is a clean, correct answer.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("No account found with id '" + accountId + "'");
    }
}
