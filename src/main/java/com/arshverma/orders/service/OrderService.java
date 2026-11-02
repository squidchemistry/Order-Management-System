package com.arshverma.orders.service;

import com.arshverma.orders.domain.Order;
import com.arshverma.orders.domain.OrderNotFoundException;
import com.arshverma.orders.domain.OrderStatus;
import com.arshverma.orders.repository.OrderRepository;
import com.arshverma.orders.web.dto.OrderResponse;
import com.arshverma.orders.web.dto.PageResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;
    private final Counter createdCounter;
    private final Counter replayCounter;
    private final Counter confirmedCounter;
    private final Counter cancelledCounter;

    public OrderService(OrderRepository orders, Clock clock, PlatformTransactionManager txManager,
                        MeterRegistry meters) {
        this.orders = orders;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.createdCounter = meters.counter("orders.created");
        this.replayCounter = meters.counter("orders.idempotent.replays");
        this.confirmedCounter = meters.counter("orders.confirmed");
        this.cancelledCounter = meters.counter("orders.cancelled");
    }

    /**
     * Creates an order. With an Idempotency-Key, a retry of the same request returns the original
     * order instead of creating a second one; the same key with a different body is rejected.
     * <p>
     * Not annotated with @Transactional on purpose: when two requests with the same key race, the
     * loser's insert fails on the unique index and its transaction is rolled back, so the lookup of
     * the winner's order has to run in a fresh transaction.
     */
    public CreateOrderResult create(CreateOrderCommand command, String idempotencyKey) {
        if (idempotencyKey == null) {
            return created(writeTx.execute(status -> insert(command, null, null)));
        }
        String fingerprint = command.fingerprint();
        Optional<CreateOrderResult> replay = findReplay(idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return created(writeTx.execute(status -> insert(command, idempotencyKey, fingerprint)));
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same key committed first.
            return findReplay(idempotencyKey, fingerprint).orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return OrderResponse.from(load(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(OrderStatus status, String customerId, Pageable pageable) {
        Specification<Order> filter = Specification.allOf(
                OrderRepository.hasStatus(status), OrderRepository.hasCustomer(customerId));
        return PageResponse.from(orders.findAll(filter, pageable).map(OrderResponse::from));
    }

    @Transactional
    public OrderResponse confirm(Long id, Long expectedVersion) {
        Order order = loadForUpdate(id, expectedVersion);
        order.confirm(clock.instant());
        // Flush inside the method so a lost optimistic-lock race surfaces here (as
        // ObjectOptimisticLockingFailureException) and the response carries the new version.
        orders.flush();
        confirmedCounter.increment();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse cancel(Long id, String reason, Long expectedVersion) {
        Order order = loadForUpdate(id, expectedVersion);
        order.cancel(reason, clock.instant());
        orders.flush();
        cancelledCounter.increment();
        return OrderResponse.from(order);
    }

    private OrderResponse insert(CreateOrderCommand command, String idempotencyKey, String fingerprint) {
        Order order = Order.create(command.customerId(), command.currency(), command.items(), clock.instant());
        if (idempotencyKey != null) {
            order.assignIdempotencyKey(idempotencyKey, fingerprint);
        }
        return OrderResponse.from(orders.saveAndFlush(order));
    }

    private Optional<CreateOrderResult> findReplay(String idempotencyKey, String fingerprint) {
        Optional<OrderResponse> existing = readTx.execute(status -> orders.findByIdempotencyKey(idempotencyKey)
                .map(order -> {
                    if (!order.getRequestFingerprint().equals(fingerprint)) {
                        throw new IdempotencyKeyReusedException(idempotencyKey, order.getId());
                    }
                    return OrderResponse.from(order);
                }));
        existing.ifPresent(o -> replayCounter.increment());
        return existing.map(order -> new CreateOrderResult(order, true));
    }

    private CreateOrderResult created(OrderResponse order) {
        createdCounter.increment();
        return new CreateOrderResult(order, false);
    }

    private Order load(Long id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private Order loadForUpdate(Long id, Long expectedVersion) {
        Order order = load(id);
        if (expectedVersion != null && expectedVersion != order.getVersion()) {
            throw new VersionMismatchException(id, expectedVersion, order.getVersion());
        }
        return order;
    }
}
