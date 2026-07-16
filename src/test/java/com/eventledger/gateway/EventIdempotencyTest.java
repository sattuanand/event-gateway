package com.eventledger.gateway;

import com.eventledger.gateway.domain.EventStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Requirement 1: idempotency")
class EventIdempotencyTest extends AbstractIntegrationTest {

    @BeforeEach
    void stubHealthyAccountService() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));
    }

    @Test
    @DisplayName("first submission is 201, resubmission is 200 and creates nothing new")
    void duplicateSubmissionIsIdempotent() {
        Map<String, Object> event = TestEvents.valid().build();

        ResponseEntity<Map> first = restTemplate.postForEntity("/events", event, Map.class);
        ResponseEntity<Map> second = restTemplate.postForEntity("/events", event, Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventRepository.count()).isOne();
    }

    @Test
    @DisplayName("a duplicate returns the ORIGINAL event, not the resubmitted payload")
    void duplicateReturnsOriginalNotResubmittedPayload() {
        restTemplate.postForEntity("/events", TestEvents.valid().build(), Map.class);

        // Same eventId, different amount. A naive upsert would silently overwrite the balance-
        // affecting field and no one would ever notice.
        Map<String, Object> tampered = TestEvents.valid().with("amount", 999999.99).build();
        ResponseEntity<Map> response = restTemplate.postForEntity("/events", tampered, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("amount", 150.00);
        assertThat(eventRepository.findById("evt-001").orElseThrow().getAmount())
                .isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("a duplicate does not re-apply the transaction downstream")
    void duplicateIsNotForwardedTwice() {
        Map<String, Object> event = TestEvents.valid().build();

        restTemplate.postForEntity("/events", event, Map.class);
        restTemplate.postForEntity("/events", event, Map.class);
        restTemplate.postForEntity("/events", event, Map.class);

        ACCOUNT_SERVICE.verify(1, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    @DisplayName("concurrent identical submissions produce exactly one event and one downstream call")
    void concurrentDuplicatesAreSafe() throws Exception {
        Map<String, Object> event = TestEvents.valid().build();
        int threads = 8;

        // This is the test that check-then-insert cannot pass. Eight threads race past an
        // existsById() gate together; only a database-level unique constraint actually serialises them.
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<ResponseEntity<Map>>> tasks = IntStream.range(0, threads)
                    .<Callable<ResponseEntity<Map>>>mapToObj(i ->
                            () -> restTemplate.postForEntity("/events", event, Map.class))
                    .toList();

            List<Future<ResponseEntity<Map>>> results = pool.invokeAll(tasks);

            long created = 0;
            long ok = 0;
            for (Future<ResponseEntity<Map>> result : results) {
                HttpStatus status = (HttpStatus) result.get().getStatusCode();
                if (status == HttpStatus.CREATED) {
                    created++;
                } else if (status == HttpStatus.OK) {
                    ok++;
                }
            }

            assertThat(created).as("exactly one submission may win").isOne();
            assertThat(ok).as("every loser must see a clean duplicate response").isEqualTo(threads - 1);
        }

        assertThat(eventRepository.count()).isOne();
        ACCOUNT_SERVICE.verify(1, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    @DisplayName("eventId is the sole dedup key: reusing it under a different accountId returns the "
            + "ORIGINAL account's event rather than silently applying to the new account")
    void duplicateEventIdUnderDifferentAccountReturnsOriginalAccount() {
        restTemplate.postForEntity("/events",
                TestEvents.valid().with("accountId", "acct-original").build(), Map.class);

        ResponseEntity<Map> response = restTemplate.postForEntity("/events",
                TestEvents.valid().with("accountId", "acct-different").build(), Map.class);

        // The caller can detect the mismatch by comparing the returned accountId to what they sent,
        // but the API does not itself reject or flag it — eventId uniqueness is global, not
        // per-account. Documented here as current behaviour rather than assumed.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("accountId", "acct-original");
        assertThat(eventRepository.count()).isOne();
        ACCOUNT_SERVICE.verify(1, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    @DisplayName("an event that failed downstream is re-driven on resubmit, not silently swallowed")
    void failedEventIsRetriedOnResubmit() {
        ACCOUNT_SERVICE.resetAll();
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        Map<String, Object> event = TestEvents.valid().build();
        ResponseEntity<String> failed = restTemplate.postForEntity("/events", event, String.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(eventRepository.findById("evt-001").orElseThrow().getStatus())
                .isEqualTo(EventStatus.FAILED);

        // The Account Service recovers, the upstream retries as our 503 invited it to.
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
        ACCOUNT_SERVICE.resetAll();
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        ResponseEntity<Map> recovered = restTemplate.postForEntity("/events", event, Map.class);

        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventRepository.findById("evt-001").orElseThrow().getStatus())
                .isEqualTo(EventStatus.APPLIED);
        assertThat(eventRepository.count()).isOne();
    }
}
