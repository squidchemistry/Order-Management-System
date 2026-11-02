package com.arshverma.orders.service;

import com.arshverma.orders.domain.LineItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public record CreateOrderCommand(String customerId, String currency, List<LineItem> items) {

    public CreateOrderCommand {
        items = List.copyOf(items);
    }

    /**
     * Stable hash of the request content. Two requests that mean the same order produce the same
     * fingerprint (e.g. "10.5" and "10.50" are treated as equal), so a retry is recognised as a replay.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder()
                .append(customerId).append('\n')
                .append(currency).append('\n');
        for (LineItem item : items) {
            canonical.append(item.sku()).append('|')
                    .append(item.productName()).append('|')
                    .append(item.quantity()).append('|')
                    .append(item.unitPrice().stripTrailingZeros().toPlainString()).append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
