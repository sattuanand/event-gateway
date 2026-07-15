package com.eventledger.gateway.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "account-service")
public record AccountServiceProperties(
        @DefaultValue("http://localhost:8081") String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout) {
}
