package com.eventledger.gateway;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 3. The Gateway generates a trace ID and forwards it on the outbound hop, so a single
 * client request can be followed across both services' logs.
 *
 * <p>Propagation uses the W3C {@code traceparent} header rather than a bespoke {@code X-Trace-Id},
 * because that is what every OpenTelemetry-aware tool already understands — including the Account
 * Service, once it exists, which will pick the trace up for free just by having the same tracing
 * starter on its classpath.
 */
@DisplayName("Requirement 3: distributed tracing")
class TracePropagationTest extends AbstractIntegrationTest {

    @BeforeEach
    void stubHealthyAccountService() {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));
    }

    @Test
    @DisplayName("the outbound call to the Account Service carries a W3C traceparent header")
    void traceIsPropagatedDownstream() {
        restTemplate.postForEntity("/events", TestEvents.valid().build(), Map.class);

        List<LoggedRequest> requests =
                ACCOUNT_SERVICE.findAll(postRequestedFor(urlPathMatching("/accounts/.*/transactions")));

        assertThat(requests).hasSize(1);
        String traceparent = requests.get(0).getHeader("traceparent");
        assertThat(traceparent).as("traceparent must be forwarded to the downstream service").isNotBlank();
        // Format: version-traceid-spanid-flags, e.g. 00-4bf92f...-00f067...-01
        assertThat(traceparent).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    }

    @Test
    @DisplayName("the trace ID sent downstream is the same one echoed back to the caller")
    void responseTraceIdMatchesDownstreamTraceId() {
        ResponseEntity<Map> response =
                restTemplate.postForEntity("/events", TestEvents.valid().build(), Map.class);

        String returnedTraceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(returnedTraceId).isNotBlank();

        LoggedRequest downstream =
                ACCOUNT_SERVICE.findAll(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))).get(0);

        // One ID ties the client's response, the Gateway's logs and the Account Service's logs together.
        assertThat(downstream.getHeader("traceparent")).contains(returnedTraceId);
    }

    @Test
    @DisplayName("error responses carry the trace ID so a caller can quote it in a ticket")
    void errorResponsesIncludeTraceId() {
        ResponseEntity<Map> response = restTemplate.postForEntity("/events",
                TestEvents.valid().with("amount", -5).build(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("traceId")).isNotNull();
    }
}
