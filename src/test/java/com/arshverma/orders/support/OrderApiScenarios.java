package com.arshverma.orders.support;

import com.arshverma.orders.repository.OrderRepository;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end HTTP scenarios shared by the H2 test (dev profile) and the Testcontainers Postgres test
 * (prod profile), so both databases are held to exactly the same behaviour.
 */
public final class OrderApiScenarios {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() { };

    private final TestRestTemplate http;
    private final BarrierClock clock;
    private final OrderRepository repository;

    public OrderApiScenarios(TestRestTemplate http, BarrierClock clock, OrderRepository repository) {
        this.http = http;
        this.clock = clock;
        this.repository = repository;
    }

    public static String body(String customerId) {
        return """
                {"customerId":"%s","currency":"INR","items":[
                  {"sku":"SKU-KB-01","productName":"Mechanical keyboard","quantity":2,"unitPrice":3499.00},
                  {"sku":"SKU-MS-02","productName":"Wireless mouse","quantity":1,"unitPrice":799.50}]}"""
                .formatted(customerId);
    }

    public ResponseEntity<Map<String, Object>> create(String customerId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange("/api/orders", HttpMethod.POST, new HttpEntity<>(body(customerId), headers), JSON);
    }

    public ResponseEntity<Map<String, Object>> post(String path) {
        return http.exchange(path, HttpMethod.POST, HttpEntity.EMPTY, JSON);
    }

    public ResponseEntity<Map<String, Object>> get(String path) {
        return http.exchange(path, HttpMethod.GET, HttpEntity.EMPTY, JSON);
    }

    public static long id(ResponseEntity<Map<String, Object>> response) {
        return ((Number) response.getBody().get("id")).longValue();
    }

    /** create → get → confirm → cancel → cancel again (409), checking totals and the list filters on the way. */
    public void fullLifecycle() {
        String customer = "cust-" + UUID.randomUUID();
        ResponseEntity<Map<String, Object>> created = create(customer, null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getBody()).containsEntry("status", "PENDING");
        // 2 x 3499.00 + 1 x 799.50, computed by the server
        assertThat(((Number) created.getBody().get("totalAmount")).doubleValue()).isEqualTo(7797.50);
        long id = id(created);

        assertThat(get("/api/orders/" + id).getBody()).containsEntry("customerId", customer);

        ResponseEntity<Map<String, Object>> confirmed = post("/api/orders/" + id + "/confirm");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).containsEntry("status", "CONFIRMED").containsEntry("version", 1);
        assertThat(confirmed.getHeaders().getETag()).isEqualTo("\"1\"");

        ResponseEntity<Map<String, Object>> confirmAgain = post("/api/orders/" + id + "/confirm");
        assertThat(confirmAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirmAgain.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(confirmAgain.getBody()).containsEntry("type", "/problems/invalid-state-transition");

        ResponseEntity<Map<String, Object>> cancelled = post("/api/orders/" + id + "/cancel");
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELLED");

        assertThat(post("/api/orders/" + id + "/cancel").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/api/orders/999999999").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        create(customer, null);
        ResponseEntity<Map<String, Object>> pending = get("/api/orders?customerId=" + customer + "&status=PENDING");
        assertThat(pending.getBody()).containsEntry("totalElements", 1);
        ResponseEntity<Map<String, Object>> all = get("/api/orders?customerId=" + customer + "&size=1");
        assertThat(all.getBody()).containsEntry("totalElements", 2).containsEntry("totalPages", 2)
                .containsEntry("hasNext", true);
    }

    /** Two confirms that both read version 0 before either writes: exactly one wins, the other gets 409. */
    public void parallelConfirmsYieldExactlyOneConflict() throws Exception {
        long id = id(create("cust-" + UUID.randomUUID(), null));

        List<ResponseEntity<Map<String, Object>>> responses;
        clock.arm(2);
        try {
            responses = runConcurrently(2, () -> post("/api/orders/" + id + "/confirm"));
        } finally {
            clock.disarm();
        }

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        ResponseEntity<Map<String, Object>> loser = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow();
        assertThat(loser.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(loser.getBody())
                .containsEntry("type", "/problems/concurrent-modification")
                .containsEntry("title", "Concurrent modification");

        ResponseEntity<Map<String, Object>> after = get("/api/orders/" + id);
        assertThat(after.getBody()).containsEntry("status", "CONFIRMED").containsEntry("version", 1);
    }

    /** Several simultaneous retries with one Idempotency-Key create one order; all callers get its id. */
    public void parallelRetriesWithSameIdempotencyKeyCreateOneOrder(int parallelism) throws Exception {
        String customer = "cust-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        List<ResponseEntity<Map<String, Object>>> responses = runConcurrently(parallelism, () -> create(customer, key));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .allMatch(s -> s == HttpStatus.CREATED || s == HttpStatus.OK);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses).extracting(OrderApiScenarios::id).containsOnly(id(responses.get(0)));
        assertThat(repository.findByIdempotencyKey(key)).isPresent();

        ResponseEntity<Map<String, Object>> otherBody = create(customer + "-other", key);
        assertThat(otherBody.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
