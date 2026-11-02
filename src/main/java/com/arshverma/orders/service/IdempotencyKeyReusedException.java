package com.arshverma.orders.service;

/** The Idempotency-Key was already used for a different request body. Mapped to HTTP 422. */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String idempotencyKey;
    private final Long existingOrderId;

    public IdempotencyKeyReusedException(String idempotencyKey, Long existingOrderId) {
        super("Idempotency-Key '%s' was already used for a different request".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
        this.existingOrderId = existingOrderId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getExistingOrderId() {
        return existingOrderId;
    }
}
