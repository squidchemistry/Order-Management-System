package com.arshverma.orders.web.dto;

import com.arshverma.orders.domain.Order;
import com.arshverma.orders.domain.OrderLine;
import com.arshverma.orders.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String customerId,
        OrderStatus status,
        String currency,
        BigDecimal totalAmount,
        List<Line> items,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt,
        Instant cancelledAt,
        long version) {

    public record Line(int lineNumber, String sku, String productName, int quantity,
                       BigDecimal unitPrice, BigDecimal lineTotal) {

        static Line from(OrderLine line) {
            return new Line(line.getLineNumber(), line.getSku(), line.getProductName(), line.getQuantity(),
                    line.getUnitPrice(), line.getLineTotal());
        }
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getCurrency(),
                order.getTotalAmount(),
                order.getLines().stream().map(Line::from).toList(),
                order.getCancellationReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getVersion());
    }
}
