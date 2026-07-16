package com.eventledger.gateway.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The Gateway's only outbound dependency.
 *
 * <p>Resiliency stack, outermost first (this is Resilience4j's documented default aspect order):
 * <pre>
 *   Retry ( CircuitBreaker ( actual HTTP call ) )
 * </pre>
 * Retry is configured to IGNORE {@code CallNotPermittedException}, so once the breaker is open we
 * fail fast instead of dutifully retrying a call that was never going to be attempted.
 *
 * <p>The fallback runs on the outermost aspect (Retry), i.e. only after retries are exhausted or
 * the breaker rejected the call outright.
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestClient restClient;

    public AccountServiceClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    @Retry(name = "accountService", fallbackMethod = "applyTransactionFallback")
    @CircuitBreaker(name = "accountService")
    public void applyTransaction(String accountId, TransactionRequest request) {
        log.debug("Calling account-service to apply transaction eventId={} accountId={}",
                request.eventId(), accountId);
        restClient.post()
                .uri("/accounts/{accountId}/transactions", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Resilience4j's fallback decorator catches Throwable, so it also sees 4xx responses that Retry
     * was told to ignore. Those are NOT an outage and must not be laundered into a 503.
     */
    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    private void applyTransactionFallback(String accountId, TransactionRequest request, Throwable t) {
        if (t instanceof HttpClientErrorException clientError) {
            log.error("account-service rejected eventId={} accountId={} status={} body={}",
                    request.eventId(), accountId, clientError.getStatusCode(),
                    clientError.getResponseBodyAsString());
            throw new AccountServiceRejectedException(
                    "Account Service rejected the transaction: " + clientError.getStatusCode(),
                    clientError.getStatusCode().value(), clientError);
        }
        log.warn("account-service unavailable for eventId={} accountId={} cause={}",
                request.eventId(), accountId, t.toString());
        throw new AccountServiceUnavailableException(
                "Account Service is unavailable. The event has been stored and can be safely "
                        + "resubmitted with the same eventId once the service recovers.", t);
    }

    /** Same resiliency posture as {@link #applyTransaction}: same breaker, same retry, same rules. */
    @Retry(name = "accountService", fallbackMethod = "getBalanceFallback")
    @CircuitBreaker(name = "accountService")
    public AccountBalance getBalance(String accountId) {
        log.debug("Calling account-service for balance accountId={}", accountId);
        return restClient.get()
                .uri("/accounts/{accountId}", accountId)
                .retrieve()
                .body(AccountBalance.class);
    }

    /**
     * A 404 here means account-service is healthy and correctly told us there's no such account —
     * that must surface as OUR OWN 404, never as a 503 "Account Service is unavailable" (which would
     * wrongly suggest retrying later would help) or a 502 (which implies we sent something invalid).
     */
    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    private AccountBalance getBalanceFallback(String accountId, Throwable t) {
        if (t instanceof HttpClientErrorException.NotFound) {
            throw new AccountNotFoundException(accountId);
        }
        if (t instanceof HttpClientErrorException clientError) {
            log.error("account-service rejected balance lookup accountId={} status={} body={}",
                    accountId, clientError.getStatusCode(), clientError.getResponseBodyAsString());
            throw new AccountServiceRejectedException(
                    "Account Service rejected the balance lookup: " + clientError.getStatusCode(),
                    clientError.getStatusCode().value(), clientError);
        }
        log.warn("account-service unavailable for balance lookup accountId={} cause={}", accountId, t.toString());
        throw new AccountServiceUnavailableException(
                "Account Service is unreachable. The balance for account '" + accountId
                        + "' cannot be retrieved right now; try again once the service recovers.", t);
    }
}
