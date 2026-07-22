package com.eventledger.gateway.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code interval-ms} is deliberately not a field here: {@link OutboxSweeper}'s {@code @Scheduled}
 * annotation reads it directly via a {@code ${event-gateway.outbox-sweeper.interval-ms:...}}
 * property placeholder, since Spring resolves the schedule interval at proxy-creation time, before
 * any {@code @ConfigurationProperties} bean is available to inject.
 */
@ConfigurationProperties(prefix = "event-gateway.outbox-sweeper")
public record OutboxSweeperProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT2M") Duration staleAfter,
        @DefaultValue("50") int batchSize,
        @DefaultValue("10") int maxRedriveAttempts) {
}
