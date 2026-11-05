package com.arshverma.orders;

import com.arshverma.orders.repository.OrderRepository;
import com.arshverma.orders.support.BarrierClock;
import com.arshverma.orders.support.OrderApiScenarios;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full app on a random port with the dev profile (H2, Flyway, demo seed data). Metrics export is off in
 * Spring Boot tests by default; {@code @AutoConfigureObservability} turns it back on so /actuator/prometheus exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(BarrierClock.Config.class)
class OrderApiH2Test {

    @Autowired
    TestRestTemplate http;

    @Autowired
    BarrierClock clock;

    @Autowired
    OrderRepository repository;

    OrderApiScenarios scenarios;

    @BeforeEach
    void setUp() {
        scenarios = new OrderApiScenarios(http, clock, repository);
    }

    @Test
    void fullLifecycle() {
        scenarios.fullLifecycle();
    }

    @Test
    void twoParallelConfirmsGiveOne200AndOne409() throws Exception {
        scenarios.parallelConfirmsYieldExactlyOneConflict();
    }

    @Test
    void parallelRetriesWithSameIdempotencyKeyCreateOneOrder() throws Exception {
        scenarios.parallelRetriesWithSameIdempotencyKeyCreateOneOrder(8);
    }

    @Test
    void demoDataIsSeeded() {
        ResponseEntity<Map<String, Object>> page = scenarios.get("/api/orders?customerId=cust-1001");
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) page.getBody().get("totalElements")).intValue()).isEqualTo(2);
    }

    @Test
    void healthProbesAndOpenApiAreExposed() {
        assertThat(scenarios.get("/actuator/health/liveness").getBody()).containsEntry("status", "UP");
        assertThat(scenarios.get("/actuator/health/readiness").getBody()).containsEntry("status", "UP");
        assertThat(http.getForEntity("/actuator/prometheus", String.class).getBody())
                .contains("orders_placed_total");
        assertThat(http.getForEntity("/v3/api-docs", String.class).getBody())
                .contains("\"/api/orders/{id}/confirm\"");
        assertThat(http.getForEntity("/swagger-ui/index.html", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
