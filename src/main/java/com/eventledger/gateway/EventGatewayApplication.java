package com.eventledger.gateway;

import com.eventledger.gateway.client.AccountServiceProperties;
import com.eventledger.gateway.service.OutboxSweeperProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({AccountServiceProperties.class, OutboxSweeperProperties.class})
@EnableScheduling
public class EventGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventGatewayApplication.class, args);
    }
}
