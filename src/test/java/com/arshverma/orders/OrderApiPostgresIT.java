package com.arshverma.orders;

import com.arshverma.orders.repository.OrderRepository;
import com.arshverma.orders.support.BarrierClock;
import com.arshverma.orders.support.OrderApiScenarios;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same scenarios as {@link OrderApiH2Test}, against a real PostgreSQL 16 with the prod profile and
 * Flyway. Run by the failsafe plugin in `mvn verify`; skipped (not failed) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Import(BarrierClock.Config.class)
class OrderApiPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    BarrierClock clock;

    @Autowired
    OrderRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    OrderApiScenarios scenarios;

    @BeforeEach
    void setUp() {
        scenarios = new OrderApiScenarios(http, clock, repository);
    }

    @Test
    void runsOnPostgresWithFlywaySchema() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL 16");
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version = '1'", Integer.class))
                .isEqualTo(1);
        assertThat(scenarios.get("/actuator/health/readiness").getBody()).containsEntry("status", "UP");
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
}
