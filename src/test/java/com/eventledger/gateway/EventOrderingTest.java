package com.eventledger.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Requirement 1: out-of-order tolerance")
class EventOrderingTest extends AbstractIntegrationTest {

    @BeforeEach
    void stubHealthyAccountService() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));
    }

    private List<Map<String, Object>> listEvents(String accountId) {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/events?account=" + accountId, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    @DisplayName("listing is chronological by eventTimestamp even when arrival order is reversed")
    void listingIsChronologicalRegardlessOfArrivalOrder() {
        // Submitted newest-first, on purpose. This is the mainframe-batch scenario: a later event
        // wins the race to our socket while an earlier one is still in flight.
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-late").with("eventTimestamp", "2026-05-15T18:00:00Z").build(), Map.class);
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-early").with("eventTimestamp", "2026-05-15T08:00:00Z").build(), Map.class);
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-middle").with("eventTimestamp", "2026-05-15T12:00:00Z").build(), Map.class);

        List<Map<String, Object>> events = listEvents("acct-123");

        assertThat(events).extracting(e -> e.get("eventId"))
                .containsExactly("evt-early", "evt-middle", "evt-late");
    }

    @Test
    @DisplayName("events for other accounts are not returned")
    void listingIsScopedToTheAccount() {
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-a").with("accountId", "acct-A").build(), Map.class);
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-b").with("accountId", "acct-B").build(), Map.class);

        assertThat(listEvents("acct-A")).extracting(e -> e.get("eventId")).containsExactly("evt-a");
        assertThat(listEvents("acct-B")).extracting(e -> e.get("eventId")).containsExactly("evt-b");
    }

    @Test
    @DisplayName("identical timestamps still produce a stable, total ordering")
    void identicalTimestampsAreOrderedDeterministically() {
        String sameInstant = "2026-05-15T10:00:00Z";
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-z").with("eventTimestamp", sameInstant).build(), Map.class);
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-a").with("eventTimestamp", sameInstant).build(), Map.class);

        // Ties break on eventId, so repeated calls cannot disagree with each other.
        assertThat(listEvents("acct-123")).extracting(e -> e.get("eventId"))
                .containsExactly("evt-a", "evt-z");
        assertThat(listEvents("acct-123")).extracting(e -> e.get("eventId"))
                .containsExactly("evt-a", "evt-z");
    }

    @Test
    @DisplayName("an account with no events returns an empty list, not a 404")
    void unknownAccountReturnsEmptyList() {
        assertThat(listEvents("acct-nobody")).isEmpty();
    }
}
