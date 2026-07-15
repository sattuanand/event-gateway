package com.eventledger.gateway;

import com.eventledger.gateway.domain.EventStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 5 and 6. The point of these tests is not that a circuit breaker bean exists — it is
 * that the Gateway's OBSERVABLE behaviour changes correctly when the downstream goes away.
 */
@DisplayName("Requirements 5 & 6: resiliency and graceful degradation")
class ResiliencyTest extends AbstractIntegrationTest {

    private void accountServiceIsFailing() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));
    }

    private void accountServiceIsHealthy() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));
    }

    private ResponseEntity<String> submit(String eventId) {
        return restTemplate.postForEntity("/events",
                TestEvents.valid().with("eventId", eventId).build(), String.class);
    }

    @Test
    @DisplayName("a failing Account Service produces 503, not 500 and not a hang")
    void downstreamFailureIsA503() {
        accountServiceIsFailing();

        ResponseEntity<String> response = submit("evt-fail");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("Account Service is unavailable");
    }

    @Test
    @DisplayName("the event is still durably stored (as FAILED) even when the downstream call fails")
    void eventSurvivesDownstreamFailure() {
        accountServiceIsFailing();

        submit("evt-durable");

        assertThat(eventRepository.findById("evt-durable")).isPresent()
                .get().extracting("status").isEqualTo(EventStatus.FAILED);
    }

    @Test
    @DisplayName("repeated failures open the circuit, after which calls fail fast without touching the network")
    void circuitOpensAndThenFailsFast() {
        accountServiceIsFailing();
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("accountService");

        // Test config: 3 retry attempts per submission, window of 6, so two submissions fill it.
        submit("evt-1");
        submit("evt-2");

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBeforeOpen = ACCOUNT_SERVICE.getAllServeEvents().size();
        ResponseEntity<String> afterOpen = submit("evt-3");

        assertThat(afterOpen.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        // The real proof: no additional traffic reached the struggling service at all. A retry-only
        // strategy would have hit it three more times and made the outage worse.
        assertThat(ACCOUNT_SERVICE.getAllServeEvents()).hasSize(callsBeforeOpen);
    }

    @Test
    @DisplayName("timeouts are treated as failures rather than parking the request thread")
    void slowDownstreamIsTimedOut() {
        // read-timeout is 1s in the test profile.
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(3000)));

        long start = System.currentTimeMillis();
        ResponseEntity<String> response = submit("evt-slow");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(elapsed).as("must not wait for the full downstream delay on every attempt")
                .isLessThan(9000);
    }

    @Test
    @DisplayName("retry re-attempts a transient failure before giving up")
    void retryIsAppliedBeforeFailing() {
        accountServiceIsFailing();

        submit("evt-retry");

        // max-attempts: 3 in the test profile.
        ACCOUNT_SERVICE.verify(3, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    @DisplayName("a 4xx from the Account Service is a 502, and must NOT open the circuit")
    void downstreamRejectionDoesNotTripTheBreaker() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(400)));
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("accountService");

        for (int i = 0; i < 8; i++) {
            ResponseEntity<String> response = submit("evt-rejected-" + i);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }

        // A malformed payload from one caller must not deny service to everyone else.
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("GET /events/{id} keeps working while the Account Service is down")
    void readsByIdSurviveOutage() {
        accountServiceIsHealthy();
        submit("evt-readable");
        accountServiceIsFailing();

        ResponseEntity<Map> response = restTemplate.getForEntity("/events/evt-readable", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("eventId", "evt-readable");
    }

    @Test
    @DisplayName("GET /events?account= keeps working while the Account Service is down")
    void listingSurvivesOutage() {
        accountServiceIsHealthy();
        submit("evt-listed");
        ACCOUNT_SERVICE.resetAll(); // downstream is now gone entirely

        ResponseEntity<String> response = restTemplate.getForEntity("/events?account=acct-123", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("evt-listed");
    }

    @Test
    @DisplayName("the Gateway stays UP when only its downstream is down")
    void healthReflectsGatewayNotDownstream() {
        accountServiceIsFailing();
        submit("evt-h1");
        submit("evt-h2");

        ResponseEntity<Map> response = restTemplate.getForEntity("/health", Map.class);

        // An orchestrator must not restart the Gateway because someone else's service is broken.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }
}
