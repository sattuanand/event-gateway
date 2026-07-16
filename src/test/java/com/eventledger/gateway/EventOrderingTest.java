package com.eventledger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
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

    /**
     * The Gateway never computes a balance itself — it forwards each event to the Account Service
     * and durably records it. {@code label} names the WireMock scenario so this stub can't be
     * confused with the class-wide {@link #stubHealthyAccountService()} one across parallel tests.
     */
    private void stubAccountServiceBalanceTracking(String label) {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario(label)
                .willReturn(aResponse().withStatus(201)));
    }

    /**
     * Reconstructs "the balance account-service would report" purely from WireMock's own request
     * journal — the only input the real ledger math (verified separately in account-service's own
     * tests) depends on. This is a stand-in for "the ledger would end up correct", not a re-test of
     * account-service's arithmetic: it proves the Gateway handed downstream exactly what was
     * submitted, for every event, regardless of the order they arrived in.
     */
    private Map<String, Object> getAccountBalance(String accountId) {
        BigDecimal net = BigDecimal.ZERO;
        for (ServeEvent event : ACCOUNT_SERVICE.getAllServeEvents()) {
            if (!event.getRequest().getUrl().equals("/accounts/" + accountId + "/transactions")) {
                continue;
            }
            JsonNode body = readJson(event.getRequest().getBodyAsString());
            BigDecimal amount = body.get("amount").decimalValue();
            net = "CREDIT".equals(body.get("type").asText()) ? net.add(amount) : net.subtract(amount);
        }
        return Map.of("accountId", accountId, "balance", net.intValueExact(), "currency", "USD");
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    @Test
    @DisplayName("a debit that arrives before its earlier credit still lists credit-then-debit")
    void debitArrivingBeforeItsCreditStillListsChronologically() {
        stubAccountServiceBalanceTracking("evt-ordering-balance");

        // Real-world timing: credit posts at 08:30, debit at 08:35 five minutes later. But the debit's
        // event notification reaches us first — the credit is still in flight upstream.
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-debit").with("type", "DEBIT").with("amount", 50.00)
                .with("eventTimestamp", "2026-05-15T08:35:00Z").build(), Map.class);
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-credit").with("type", "CREDIT").with("amount", 200.00)
                .with("eventTimestamp", "2026-05-15T08:30:00Z").build(), Map.class);

        List<Map<String, Object>> events = listEvents("acct-123");

        assertThat(events).extracting(e -> e.get("eventId"))
                .containsExactly("evt-credit", "evt-debit");

        // Reversed arrival must not corrupt either event's own data along the way.
        ResponseEntity<Map> credit = restTemplate.getForEntity("/events/evt-credit", Map.class);
        ResponseEntity<Map> debit = restTemplate.getForEntity("/events/evt-debit", Map.class);
        assertThat(credit.getBody()).containsEntry("type", "CREDIT").containsEntry("amount", 200.0);
        assertThat(debit.getBody()).containsEntry("type", "DEBIT").containsEntry("amount", 50.0);

        // Both transactions have now landed downstream; the net balance is correct regardless of the
        // order they were forwarded in.
        assertThat(getAccountBalance("acct-123")).containsEntry("balance", 150);
    }

    @Test
    @DisplayName("net balance is the sum of CREDITs minus the sum of DEBITs")
    void netBalanceIsSumOfCreditsMinusSumOfDebits() {
        stubAccountServiceBalanceTracking("evt-net-balance");

        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-bal-1").with("type", "CREDIT").with("amount", 200.00).build(), Map.class);
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-bal-2").with("type", "DEBIT").with("amount", 50.00).build(), Map.class);
        restTemplate.postForEntity("/events", TestEvents.valid()
                .with("eventId", "evt-bal-3").with("type", "CREDIT").with("amount", 30.00).build(), Map.class);

        // (200 + 30) credits - 50 debit = 180
        assertThat(getAccountBalance("acct-123")).containsEntry("balance", 180);
    }
}
