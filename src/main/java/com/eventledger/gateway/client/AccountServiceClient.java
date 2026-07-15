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
        throw new AccountServiceUnavailableException("Account Service is unavailable", t);
    }
}
