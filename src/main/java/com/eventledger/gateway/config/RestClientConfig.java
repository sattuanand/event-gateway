package com.eventledger.gateway.config;

import com.eventledger.gateway.client.AccountServiceProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Built from the auto-configured {@link RestClient.Builder} on purpose: that builder carries the
     * Micrometer observation instrumentation, which is what injects the W3C {@code traceparent}
     * header onto every outbound call. Hand-rolling {@code RestClient.create()} here would silently
     * break trace propagation.
     *
     * <p>The read timeout is deliberately short. A circuit breaker cannot protect you from a
     * downstream that is slow rather than dead — without a timeout, "slow" just means every Gateway
     * request thread parks on a socket and the breaker never sees a failure to count.
     */
    @Bean
    public RestClient accountServiceRestClient(RestClient.Builder builder,
                                               AccountServiceProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
