package com.eventledger.gateway;

import com.eventledger.gateway.domain.EventRecord;
import com.eventledger.gateway.domain.EventStatus;
import com.eventledger.gateway.domain.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutboxSweeperTest} proves the sweep's business logic by calling {@code sweep()} directly.
 * This class proves the other half: that {@code event-gateway.outbox-sweeper.interval-ms} genuinely
 * drives Spring's real {@code @Scheduled} trigger, not just a config value nobody reads. It overrides
 * the interval to 200ms — far below the 5-minute production default — and never calls
 * {@code sweep()} itself; the assertion can only pass if the real scheduled timer fired on its own.
 *
 * <p>{@code @DirtiesContext} is required, not cosmetic: at 200ms this class's context has an
 * aggressively-firing background thread. Spring Test caches contexts for reuse instead of
 * destroying them eagerly, so without this the thread keeps polling the shared static
 * {@code POSTGRES}/{@code ACCOUNT_SERVICE} instances for the rest of the suite, corrupting other
 * tests' WireMock call counts. See {@link OutboxSweeperTest}'s class Javadoc for the same note.
 */
@DisplayName("Outbox sweeper: interval-ms is configurable and actually drives the real scheduled trigger")
@TestPropertySource(properties = {
        "event-gateway.outbox-sweeper.enabled=true",
        "event-gateway.outbox-sweeper.interval-ms=200"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxSweeperSchedulingTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("a FAILED event is redriven with no manual sweep() call, well inside a 5s bound")
    void realTimerFiresAtTheConfiguredInterval() throws InterruptedException {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        eventRepository.save(new EventRecord("evt-sweep-real-timer", "acct-sweep-timer", EventType.CREDIT,
                new BigDecimal("10.00"), "USD", Instant.parse("2026-05-15T08:00:00Z"),
                null, EventStatus.FAILED, Instant.now()));

        EventStatus finalStatus = EventStatus.FAILED;
        long deadline = System.currentTimeMillis() + 5000; // generous vs. the 200ms configured interval
        while (System.currentTimeMillis() < deadline) {
            finalStatus = eventRepository.findById("evt-sweep-real-timer").orElseThrow().getStatus();
            if (finalStatus == EventStatus.APPLIED) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(finalStatus).isEqualTo(EventStatus.APPLIED);
    }
}
