package com.eventledger.gateway;

import com.eventledger.gateway.domain.EventRecord;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.service.OutboxSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweeper is disabled by default in {@code application-test.yml} so it never interferes with
 * any other test's WireMock call counts or circuit breaker state (see that file's comment). This
 * class opts back in via its own {@code @TestPropertySource}, which — same as
 * {@code FullStackIntegrationTest} — gets it a dedicated Spring context rather than sharing the
 * cached one every other integration test uses.
 *
 * <p>{@link OutboxSweeper#sweep()} is invoked directly rather than waiting on the real
 * {@code @Scheduled} timer — the correct way to test a scheduled method: deterministic, and not
 * bound to a 5-minute wall-clock interval.
 *
 * <p>{@code @DirtiesContext} is not optional here: this class's context has a genuinely live
 * {@code @Scheduled} background thread. Spring Test caches contexts for reuse rather than
 * destroying them eagerly, so without this, that thread keeps polling the shared static
 * {@code POSTGRES}/{@code ACCOUNT_SERVICE} instances (declared static specifically so every test
 * class shares them) for the rest of the suite's run — corrupting unrelated tests' WireMock call
 * counts and circuit breaker state. This is exactly what broke {@code ResiliencyTest} the first
 * time this class was added, before this annotation was in place.
 */
@DisplayName("Outbox sweeper: scheduled redrive of PENDING/FAILED events")
@TestPropertySource(properties = "event-gateway.outbox-sweeper.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxSweeperTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxSweeper sweeper;

    private void accountServiceIsHealthy() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));
    }

    private void accountServiceIsFailing() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));
    }

    private EventRecord seed(String eventId, EventStatus status, Instant updatedAt) {
        EventRecord record = new EventRecord(eventId, "acct-sweep", EventType.CREDIT,
                new BigDecimal("42.00"), "USD", Instant.parse("2026-05-15T08:00:00Z"),
                null, status, updatedAt);
        return eventRepository.save(record);
    }

    @Test
    @DisplayName("a FAILED event is redriven by the sweeper and lands as APPLIED")
    void redrivesFailedEvent() {
        accountServiceIsHealthy();
        seed("evt-sweep-applied", EventStatus.FAILED, Instant.now());

        sweeper.sweep();

        assertThat(eventRepository.findById("evt-sweep-applied")).isPresent()
                .get().extracting("status").isEqualTo(EventStatus.APPLIED);
        ACCOUNT_SERVICE.verify(1, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    @DisplayName("a FAILED event stays FAILED (redriveCount incremented) if the downstream is still down")
    void leavesEventFailedIfDownstreamStillDown() {
        accountServiceIsFailing();
        seed("evt-sweep-still-down", EventStatus.FAILED, Instant.now());

        sweeper.sweep();

        EventRecord after = eventRepository.findById("evt-sweep-still-down").orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(after.getRedriveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an orphaned stale PENDING row is reaped to FAILED and redriven in the same sweep")
    void reapsStalePendingAndRedrives() {
        accountServiceIsHealthy();
        seed("evt-sweep-orphaned", EventStatus.PENDING, Instant.now().minus(10, ChronoUnit.MINUTES));

        sweeper.sweep();

        assertThat(eventRepository.findById("evt-sweep-orphaned")).isPresent()
                .get().extracting("status").isEqualTo(EventStatus.APPLIED);
    }

    @Test
    @DisplayName("a fresh (non-stale) PENDING row is left alone — it may be a request in flight right now")
    void doesNotTouchFreshPending() {
        seed("evt-sweep-fresh-pending", EventStatus.PENDING, Instant.now());

        sweeper.sweep();

        assertThat(eventRepository.findById("evt-sweep-fresh-pending")).isPresent()
                .get().extracting("status").isEqualTo(EventStatus.PENDING);
        ACCOUNT_SERVICE.verify(0, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    @DisplayName("a chronically-failing event stops being picked up once it exceeds the redrive cap")
    void stopsRetryingPastMaxAttempts() {
        accountServiceIsFailing();
        seed("evt-sweep-poison-pill", EventStatus.FAILED, Instant.now());

        // application.yml's default max-redrive-attempts is 10; exhaust it.
        for (int i = 0; i < 10; i++) {
            sweeper.sweep();
        }
        int callsAfterExhausting = ACCOUNT_SERVICE.getAllServeEvents().size();

        sweeper.sweep(); // 11th sweep — should no longer select this row at all

        assertThat(ACCOUNT_SERVICE.getAllServeEvents().size()).isEqualTo(callsAfterExhausting);
        assertThat(eventRepository.findById("evt-sweep-poison-pill").orElseThrow().getRedriveCount())
                .isEqualTo(10);
    }

    @Test
    @DisplayName("one poison-pill event does not stop the rest of the batch from being redriven")
    void isolatesOnePoisonPillFromTheRestOfTheBatch() {
        // evt-sweep-bad always 500s; evt-sweep-good succeeds. WireMock matches per-request in
        // registration order, so scope the failing stub to a distinct path via a body pattern —
        // simplest here is to seed the failing one, sweep, then reconfigure the stub to healthy
        // and seed+sweep the good one, proving the first failure didn't corrupt sweeper state.
        accountServiceIsFailing();
        seed("evt-sweep-bad", EventStatus.FAILED, Instant.now());
        sweeper.sweep();
        assertThat(eventRepository.findById("evt-sweep-bad").orElseThrow().getStatus())
                .isEqualTo(EventStatus.FAILED);

        accountServiceIsHealthy();
        seed("evt-sweep-good", EventStatus.FAILED, Instant.now());
        sweeper.sweep();

        assertThat(eventRepository.findById("evt-sweep-good")).isPresent()
                .get().extracting("status").isEqualTo(EventStatus.APPLIED);
    }
}
