package com.arshverma.orders.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    /** Injected instead of calling Instant.now() so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI orderManagementOpenApi(ObjectProvider<BuildProperties> build) {
        String version = build.getIfAvailable() == null ? "dev" : build.getIfAvailable().getVersion();
        return new OpenAPI().info(new Info()
                .title("Order Management Service")
                .version(version)
                .description("""
                        Create, retrieve, list, confirm and cancel orders. Errors are \
                        application/problem+json (RFC 9457). POST /api/orders accepts an Idempotency-Key header.""")
                .license(new License().name("MIT")));
    }
}
