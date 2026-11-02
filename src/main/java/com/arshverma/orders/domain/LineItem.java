package com.arshverma.orders.domain;

import java.math.BigDecimal;

/** Input for a new order line. The line total is never taken from the caller. */
public record LineItem(String sku, String productName, int quantity, BigDecimal unitPrice) {

    public LineItem {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (unitPrice == null || unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("unitPrice must be positive");
        }
    }
}
