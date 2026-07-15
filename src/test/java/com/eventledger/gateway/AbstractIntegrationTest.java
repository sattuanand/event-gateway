package com.eventledger.gateway;

import com.eventledger.gateway.domain.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Shared harness for every integration test.
 *
 * <p>Real Postgres via Testcontainers rather than H2 in compatibility mode: the whole point of the
 * idempotency design is that a specific database enforces a specific constraint, and I would rather
 * not discover a Postgres-vs-H2 behavioural difference in production.
 *
 * <p>WireMock stands in for the Account Service, which does not exist yet (iteration 2). That is
 * actually a feature — it lets us test the Gateway's failure handling against a downstream we can
 * make fail on demand, which is exactly what requirement 8 asks for and which is awkward to do
 * against a real, healthy service.
 *
 * <p>Container and stub server are started once in a static block and shared across all test
 * classes, rather than per-class via {@code @Testcontainers}. Postgres takes a few seconds to boot;
 * paying that once is the difference between a suite you run and a suite you avoid.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("eventledger")
                    .withUsername("eventledger")
                    .withPassword("eventledger");

    protected static final WireMockServer ACCOUNT_SERVICE =
            new WireMockServer(options().dynamicPort());

    static {
        POSTGRES.start();
        ACCOUNT_SERVICE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("account-service.base-url", () -> "http://localhost:" + ACCOUNT_SERVICE.port());
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected EventRepository eventRepository;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetState() {
        eventRepository.deleteAll();
        ACCOUNT_SERVICE.resetAll();
        // Breaker state is process-wide. Without this, whichever test happens to run after the
        // resiliency test would inherit an open circuit and fail for no visible reason.
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    @AfterEach
    void verifyNoUnexpectedState() {
        ACCOUNT_SERVICE.resetAll();
    }
}
