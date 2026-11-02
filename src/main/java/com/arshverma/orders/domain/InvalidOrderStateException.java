package com.arshverma.orders.domain;

/** Thrown when an action is not allowed from the order's current status. Mapped to HTTP 409. */
public class InvalidOrderStateException extends RuntimeException {

    private final Long orderId;
    private final OrderStatus currentStatus;
    private final OrderStatus targetStatus;

    public InvalidOrderStateException(Long orderId, OrderStatus currentStatus, OrderStatus targetStatus) {
        super("Order %s cannot move from %s to %s".formatted(orderId, currentStatus, targetStatus));
        this.orderId = orderId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public Long getOrderId() {
        return orderId;
    }

    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }

    public OrderStatus getTargetStatus() {
        return targetStatus;
    }
}
