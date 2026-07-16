package com.eventledger.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Requirement 1: validation")
class EventValidationTest extends AbstractIntegrationTest {

    // Only the "accepts ..." boundary tests below actually reach the downstream call — every
    // rejection test fails validation first and never touches this stub.
    @BeforeEach
    void stubHealthyAccountService() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));
    }

    private ResponseEntity<String> postEvent(Map<String, Object> body) {
        return restTemplate.postForEntity("/events", body, String.class);
    }

    @Test
    @DisplayName("rejects a missing required field with 400 and names the field")
    void rejectsMissingField() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().without("accountId").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("accountId");
        assertThat(eventRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "accountId", "type", "amount", "currency", "eventTimestamp"})
    @DisplayName("rejects every required field when it is missing, individually")
    void rejectsEachMissingRequiredField(String field) {
        ResponseEntity<String> response = postEvent(TestEvents.valid().without(field).build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains(field);
        assertThat(eventRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "accountId", "currency"})
    @DisplayName("rejects a blank string as distinct from a missing field")
    void rejectsBlankStringField(String field) {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with(field, "").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains(field);
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    @DisplayName("rejects an eventId over the 128 character limit")
    void rejectsOverlongEventId() {
        String tooLong = "e".repeat(129);
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("eventId", tooLong).build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("eventId");
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    @DisplayName("accepts an eventId at exactly the 128 character limit")
    void acceptsEventIdAtTheLimit() {
        String atLimit = "e".repeat(128);
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("eventId", atLimit).build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("rejects a currency code that isn't exactly 3 letters")
    void rejectsMalformedCurrencyCode() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("currency", "US").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("currency");
    }

    @Test
    @DisplayName("accepts a lower-case 3-letter currency code")
    void acceptsLowerCaseCurrencyCode() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("currency", "usd").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("amount given as a non-numeric string is a malformed-body 400, not a 500")
    void rejectsNonNumericAmount() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("amount", "not-a-number").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("metadata is optional — a valid event without it is still accepted")
    void metadataIsOptional() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().without("metadata").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a body that isn't JSON at all is rejected as malformed, not a 500")
    void rejectsNonJsonBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{this is not json", headers);

        ResponseEntity<String> response = restTemplate.postForEntity("/events", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("malformed");
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    @DisplayName("rejects a zero amount")
    void rejectsZeroAmount() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("amount", 0).build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("amount must be greater than 0");
    }

    @Test
    @DisplayName("rejects a negative amount")
    void rejectsNegativeAmount() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("amount", -10.5).build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("amount must be greater than 0");
    }

    @Test
    @DisplayName("rejects an unknown type and tells the caller what is allowed")
    void rejectsUnknownType() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("type", "TRANSFER").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("TRANSFER").contains("CREDIT").contains("DEBIT");
    }

    @Test
    @DisplayName("rejects a lower-case type — enum matching is exact, not case-insensitive")
    void rejectsLowerCaseType() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("type", "credit").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("credit").contains("CREDIT").contains("DEBIT");
    }

    @Test
    @DisplayName("rejects an explicit JSON null for type the same as a missing type")
    void rejectsExplicitNullType() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("type", null).build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("type");
    }

    @Test
    @DisplayName("a validation failure returns the full documented error shape, not just a status code")
    void validationFailureHasWellFormedErrorBody() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/events", TestEvents.valid().with("amount", -5).build(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("status", 400)
                .containsEntry("error", "Bad Request")
                .containsKeys("timestamp", "message", "details", "traceId");
        assertThat((java.util.List<?>) response.getBody().get("details")).isNotEmpty();
    }

    @Test
    @DisplayName("an unsupported Content-Type is a 415, not a 500")
    void unsupportedContentTypeIsA415() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<String> request = new HttpEntity<>("hello", headers);

        ResponseEntity<String> response = restTemplate.postForEntity("/events", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    @DisplayName("an unsupported HTTP method on a valid path is a 405, not a 500")
    void unsupportedHttpMethodIsA405() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/events/some-id", org.springframework.http.HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("rejects a malformed eventTimestamp")
    void rejectsBadTimestamp() {
        ResponseEntity<String> response = postEvent(TestEvents.valid().with("eventTimestamp", "last Tuesday").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a rejected event never reaches the Account Service")
    void invalidEventsAreNotForwarded() {
        postEvent(TestEvents.valid().with("amount", -1).build());

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
