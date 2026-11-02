package com.arshverma.orders.repository;

import com.arshverma.orders.domain.Order;
import com.arshverma.orders.domain.OrderStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    static Specification<Order> hasStatus(OrderStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<Order> hasCustomer(String customerId) {
        return (root, query, cb) -> customerId == null || customerId.isBlank()
                ? null
                : cb.equal(root.get("customerId"), customerId);
    }
}
