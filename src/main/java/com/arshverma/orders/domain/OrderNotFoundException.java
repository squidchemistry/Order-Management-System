package com.arshverma.orders.domain;

/** Mapped to HTTP 404. */
public class OrderNotFoundException extends RuntimeException {

    private final Long orderId;

    public OrderNotFoundException(Long orderId) {
        super("Order %s not found".formatted(orderId));
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
