package com.eventledger.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Requirement 1: validation")
class EventValidationTest extends AbstractIntegrationTest {

    private ResponseEntity<String> post(Map<String, Object> body) {
        return restTemplate.postForEntity("/events", body, String.class);
    }

    @Test
    @DisplayName("rejects a missing required field with 400 and names the field")
    void rejectsMissingField() {
        ResponseEntity<String> response = post(TestEvents.valid().without("accountId").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("accountId");
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    @DisplayName("rejects a zero amount")
    void rejectsZeroAmount() {
        ResponseEntity<String> response = post(TestEvents.valid().with("amount", 0).build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("amount must be greater than 0");
    }

    @Test
    @DisplayName("rejects a negative amount")
    void rejectsNegativeAmount() {
        ResponseEntity<String> response = post(TestEvents.valid().with("amount", -10.5).build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("amount must be greater than 0");
    }

    @Test
    @DisplayName("rejects an unknown type and tells the caller what is allowed")
    void rejectsUnknownType() {
        ResponseEntity<String> response = post(TestEvents.valid().with("type", "TRANSFER").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("TRANSFER").contains("CREDIT").contains("DEBIT");
    }

    @Test
    @DisplayName("rejects a malformed eventTimestamp")
    void rejectsBadTimestamp() {
        ResponseEntity<String> response = post(TestEvents.valid().with("eventTimestamp", "last Tuesday").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a rejected event never reaches the Account Service")
    void invalidEventsAreNotForwarded() {
        post(TestEvents.valid().with("amount", -1).build());

        assertThat(ACCOUNT_SERVICE.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("GET /events without the account parameter is a 400, not a 500")
    void missingAccountParam() {
        ResponseEntity<String> response = restTemplate.getForEntity("/events", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /events/{id} for an unknown id is a 404")
    void unknownEventIsNotFound() {
        ResponseEntity<String> response = restTemplate.getForEntity("/events/does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
