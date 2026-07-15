package com.eventledger.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Requirement 4: health and custom metrics")
class HealthAndMetricsTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("GET /health reports the service, the database and the breaker state")
    void healthReportsDependencies() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("service", "event-gateway");
        assertThat(response.getBody()).containsEntry("status", "UP");

        Map<String, Object> checks = (Map<String, Object>) response.getBody().get("checks");
        assertThat(checks).containsEntry("database", "UP");
        assertThat(checks).containsKey("accountServiceCircuitBreaker");
    }

    @Test
    @DisplayName("custom business metrics are exposed to Prometheus")
    void customMetricsAreExposed() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        Map<String, Object> event = TestEvents.valid().build();
        restTemplate.postForEntity("/events", event, Map.class);
        restTemplate.postForEntity("/events", event, Map.class); // duplicate

        ResponseEntity<String> metrics = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody())
                .contains("events_received_total")
                .contains("events_applied_total")
                .contains("events_duplicate_total");
    }
}
