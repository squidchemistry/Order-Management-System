package com.arshverma.orders.service;

/** The client's If-Match version is stale. Mapped to HTTP 412. */
public class VersionMismatchException extends RuntimeException {

    private final Long orderId;
    private final long expectedVersion;
    private final long actualVersion;

    public VersionMismatchException(Long orderId, long expectedVersion, long actualVersion) {
        super("Order %s is at version %d, but the request expected version %d"
                .formatted(orderId, actualVersion, expectedVersion));
        this.orderId = orderId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public Long getOrderId() {
        return orderId;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}
