package com.eventledger.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 7: at least one test that exercises the FULL Gateway -> Account Service flow against
 * the real, packaged Account Service — not WireMock. Every other integration test in this suite
 * (EventIdempotencyTest, ResiliencyTest, TracePropagationTest, ...) deliberately stubs account-service
 * with WireMock, precisely so those tests can force specific downstream failure modes on demand.
 * That is the right call for THOSE tests, but it means none of them actually proves the two real
 * services work together. This is the one that does — the automated equivalent of the manual
 * docker-compose.full.yml + curl walkthrough in the README, but asserting rather than eyeballed.
 *
 * <p>Builds the Account Service image straight from its Dockerfile in the sibling checkout
 * (../account-service — see README's "System requirements") and runs it with its 'standalone'
 * profile (embedded H2), so this test needs no second Postgres just to prove the hop works. The
 * image build is real work (a full Gradle build inside the container) and can take several minutes
 * the first time; see the README for how to run this test on its own.
 *
 * <p>Deliberately NOT built on {@link AbstractIntegrationTest} — that class's Spring context is
 * wired to a WireMock stub, and reusing it here would risk Spring's test-context cache silently
 * handing this test the SAME cached context (and therefore the stub) instead of the real service.
 * The {@code full-stack-test} marker property below forces this test into its own, never-shared
 * context so that can't happen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "full-stack-test=true")
@Testcontainers
@DisplayName("Requirement 7: full Gateway -> Account Service integration")
class FullStackIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eventledger")
            .withUsername("eventledger")
            .withPassword("eventledger");

    @Container
    static final GenericContainer<?> ACCOUNT_SERVICE = new GenericContainer<>(
            new ImageFromDockerfile("account-service-fullstack-test", false)
                    .withDockerfile(Paths.get("../account-service/Dockerfile")))
            .withExposedPorts(8081)
            .withEnv("SPRING_PROFILES_ACTIVE", "standalone")
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(5));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("account-service.base-url", () ->
                "http://" + ACCOUNT_SERVICE.getHost() + ":" + ACCOUNT_SERVICE.getMappedPort(8081));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("a client request flows through the Gateway and lands as a real transaction in the real Account Service")
    void fullGatewayToAccountServiceFlow() {
        Map<String, Object> event = TestEvents.valid().build();

        ResponseEntity<Map> submitResponse = restTemplate.postForEntity("/events", event, Map.class);
        assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Read back through the Gateway's OWN proxy endpoint, not account-service directly — this
        // proves the round trip both ways, exactly as a real client would experience it.
        ResponseEntity<Map> balanceResponse =
                restTemplate.getForEntity("/accounts/{accountId}", Map.class, event.get("accountId"));

        assertThat(balanceResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(balanceResponse.getBody().get("balance").toString()))
                .isEqualByComparingTo(new BigDecimal(event.get("amount").toString()));
    }
}
