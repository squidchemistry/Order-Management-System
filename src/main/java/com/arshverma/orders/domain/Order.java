package com.arshverma.orders.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate root. All state changes go through methods on this class so the lifecycle
 * rules and the server-side total cannot be bypassed by callers.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    /** SHA-256 of the create request, used to detect an idempotency key reused with a different body. */
    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Optimistic lock: Hibernate adds "where version = ?" to every update and bumps it. */
    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {
        // for JPA
    }

    public static Order create(String customerId, String currency, List<LineItem> items, Instant now) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO 4217 code");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line item");
        }
        Order order = new Order();
        order.customerId = customerId;
        order.currency = currency;
        order.status = OrderStatus.PENDING;
        order.createdAt = now;
        order.updatedAt = now;
        int lineNumber = 1;
        for (LineItem item : items) {
            order.lines.add(new OrderLine(order, lineNumber++, item));
        }
        order.totalAmount = order.lines.stream()
                .map(OrderLine::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return order;
    }

    public void assignIdempotencyKey(String key, String fingerprint) {
        this.idempotencyKey = Objects.requireNonNull(key);
        this.requestFingerprint = Objects.requireNonNull(fingerprint);
    }

    public void confirm(Instant now) {
        transitionTo(OrderStatus.CONFIRMED, now);
        this.confirmedAt = now;
    }

    public void cancel(String reason, Instant now) {
        transitionTo(OrderStatus.CANCELLED, now);
        this.cancelledAt = now;
        this.cancellationReason = reason;
    }

    private void transitionTo(OrderStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidOrderStateException(id, status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCurrency() {
        return currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public long getVersion() {
        return version;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
