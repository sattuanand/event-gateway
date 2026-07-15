package com.eventledger.gateway;

import com.eventledger.gateway.client.AccountServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AccountServiceProperties.class)
public class EventGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventGatewayApplication.class, args);
    }
}
