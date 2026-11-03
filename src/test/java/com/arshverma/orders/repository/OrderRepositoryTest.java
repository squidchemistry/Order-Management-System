package com.arshverma.orders.repository;

import com.arshverma.orders.domain.LineItem;
import com.arshverma.orders.domain.Order;
import com.arshverma.orders.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against the dev profile's H2 (PostgreSQL mode) with the real Flyway schema, not a Hibernate-generated one. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

    @Autowired
    OrderRepository repository;

    @Autowired
    TestEntityManager em;

    private final String customer = "cust-" + UUID.randomUUID();
    private Instant clock = Instant.parse("2026-01-01T00:00:00Z");

    private Order newOrder(String customerId, String price) {
        clock = clock.plusSeconds(60);
        return Order.create(customerId, "INR", List.of(
                new LineItem("SKU-1", "Keyboard", 2, new BigDecimal(price)),
                new LineItem("SKU-2", "Mouse", 1, new BigDecimal("5.00"))), clock);
    }

    @Test
    void persistsOrderWithLinesAndReadsItBack() {
        Order saved = repository.saveAndFlush(newOrder(customer, "10.00"));
        em.clear();

        Order found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(found.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(found.getLines()).extracting(l -> l.getSku()).containsExactly("SKU-1", "SKU-2");
        assertThat(found.getVersion()).isZero();
    }

    @Test
    void versionIncrementsOnEveryUpdate() {
        Order saved = repository.saveAndFlush(newOrder(customer, "10.00"));
        assertThat(saved.getVersion()).isZero();

        saved.confirm(clock);
        repository.flush();
        assertThat(saved.getVersion()).isEqualTo(1);

        saved.cancel("x", clock);
        repository.flush();
        assertThat(saved.getVersion()).isEqualTo(2);
    }

    @Test
    void filtersByStatusAndCustomerWithPagination() {
        String other = "cust-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            repository.save(newOrder(customer, "1" + i + ".00"));
        }
        Order confirmed = repository.save(newOrder(customer, "99.00"));
        confirmed.confirm(clock);
        repository.save(newOrder(other, "1.00"));
        repository.flush();

        Specification<Order> pendingForCustomer = Specification.allOf(
                OrderRepository.hasStatus(OrderStatus.PENDING), OrderRepository.hasCustomer(customer));
        Page<Order> page0 = repository.findAll(pendingForCustomer,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<Order> page2 = repository.findAll(pendingForCustomer,
                PageRequest.of(2, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getTotalPages()).isEqualTo(3);
        assertThat(page0.getContent()).hasSize(2)
                .allMatch(o -> o.getStatus() == OrderStatus.PENDING && o.getCustomerId().equals(customer));
        assertThat(page0.getContent().get(0).getCreatedAt()).isAfter(page0.getContent().get(1).getCreatedAt());
        assertThat(page2.getContent()).hasSize(1);

        Page<Order> allForCustomer = repository.findAll(
                Specification.allOf(OrderRepository.hasStatus(null), OrderRepository.hasCustomer(customer)),
                PageRequest.of(0, 50));
        assertThat(allForCustomer.getTotalElements()).isEqualTo(6);
    }

    @Test
    void findsByIdempotencyKey() {
        String key = UUID.randomUUID().toString();
        Order order = newOrder(customer, "10.00");
        order.assignIdempotencyKey(key, "fp");
        repository.saveAndFlush(order);

        assertThat(repository.findByIdempotencyKey(key)).map(Order::getId).contains(order.getId());
        assertThat(repository.findByIdempotencyKey("missing")).isEmpty();
    }

    @Test
    void databaseRejectsDuplicateIdempotencyKey() {
        String key = UUID.randomUUID().toString();
        Order first = newOrder(customer, "10.00");
        first.assignIdempotencyKey(key, "fp");
        repository.saveAndFlush(first);

        Order second = newOrder(customer, "10.00");
        second.assignIdempotencyKey(key, "fp");

        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
