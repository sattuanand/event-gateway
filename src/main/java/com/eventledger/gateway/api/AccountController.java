package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountBalance;
import com.eventledger.gateway.client.AccountServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A pure passthrough to Account Service — no local state, nothing to persist here. Reuses
 * {@link AccountServiceClient}'s existing circuit breaker / retry / timeout stack, so a balance
 * query fails exactly the same way the write path does: 503 while the breaker is open or the
 * downstream is unreachable, never a hang and never a raw 500.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountServiceClient accountServiceClient;

    public AccountController(AccountServiceClient accountServiceClient) {
        this.accountServiceClient = accountServiceClient;
    }

    @GetMapping("/{accountId}")
    public AccountBalance getBalance(@PathVariable("accountId") String accountId) {
        return accountServiceClient.getBalance(accountId);
    }
}
