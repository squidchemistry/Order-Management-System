package com.arshverma.orders.domain;

/**
 * Order lifecycle states. Allowed transitions:
 * <pre>
 *   PENDING ──confirm──▶ CONFIRMED
 *   PENDING ──cancel───▶ CANCELLED
 *   CONFIRMED ─cancel──▶ CANCELLED
 * </pre>
 * Everything else is rejected by {@link Order}.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == CANCELLED;
            case CANCELLED -> false;
        };
    }
}
