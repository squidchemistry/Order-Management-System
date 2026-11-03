package com.arshverma.orders.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T10:05:00Z");

    private static Order newOrder() {
        return Order.create("cust-1", "INR", List.of(
                new LineItem("SKU-1", "Keyboard", 2, new BigDecimal("10.005")),
                new LineItem("SKU-2", "Mouse", 3, new BigDecimal("0.10"))), T0);
    }

    private static Order orderIn(OrderStatus status) {
        Order order = newOrder();
        switch (status) {
            case PENDING -> { }
            case CONFIRMED -> order.confirm(T0);
            case CANCELLED -> order.cancel("test", T0);
        }
        return order;
    }

    @Test
    void newOrderIsPendingWithServerComputedTotals() {
        Order order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getLines()).hasSize(2);
        // 10.005 is rounded half-even to 10.00, so 2 x 10.00 + 3 x 0.10
        assertThat(order.getLines().get(0).getUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(order.getLines().get(0).getLineTotal()).isEqualByComparingTo("20.00");
        assertThat(order.getLines().get(1).getLineTotal()).isEqualByComparingTo("0.30");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("20.30");
        assertThat(order.getLines()).extracting(OrderLine::getLineNumber).containsExactly(1, 2);
        assertThat(order.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    void pendingToConfirmed() {
        Order order = orderIn(OrderStatus.PENDING);

        order.confirm(T1);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isEqualTo(T1);
        assertThat(order.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void pendingToCancelled() {
        Order order = orderIn(OrderStatus.PENDING);

        order.cancel("changed my mind", T1);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isEqualTo(T1);
        assertThat(order.getCancellationReason()).isEqualTo("changed my mind");
    }

    @Test
    void confirmedToCancelled() {
        Order order = orderIn(OrderStatus.CONFIRMED);

        order.cancel(null, T1);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getConfirmedAt()).isEqualTo(T0);
        assertThat(order.getCancelledAt()).isEqualTo(T1);
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @CsvSource({
            "CONFIRMED, CONFIRMED",
            "CANCELLED, CONFIRMED",
            "CANCELLED, CANCELLED"
    })
    void illegalTransitionsAreRejectedAndLeaveTheOrderUnchanged(OrderStatus from, OrderStatus to) {
        Order order = orderIn(from);
        Instant updatedBefore = order.getUpdatedAt();

        assertThatThrownBy(() -> {
            if (to == OrderStatus.CONFIRMED) {
                order.confirm(T1);
            } else {
                order.cancel("again", T1);
            }
        })
                .isInstanceOf(InvalidOrderStateException.class)
                .satisfies(e -> {
                    InvalidOrderStateException ex = (InvalidOrderStateException) e;
                    assertThat(ex.getCurrentStatus()).isEqualTo(from);
                    assertThat(ex.getTargetStatus()).isEqualTo(to);
                });
        assertThat(order.getStatus()).isEqualTo(from);
        assertThat(order.getUpdatedAt()).isEqualTo(updatedBefore);
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "PENDING, PENDING, false",
            "PENDING, CONFIRMED, true",
            "PENDING, CANCELLED, true",
            "CONFIRMED, PENDING, false",
            "CONFIRMED, CONFIRMED, false",
            "CONFIRMED, CANCELLED, true",
            "CANCELLED, PENDING, false",
            "CANCELLED, CONFIRMED, false",
            "CANCELLED, CANCELLED, false"
    })
    void fullTransitionTable(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void orderNeedsAtLeastOneLine() {
        assertThatThrownBy(() -> Order.create("cust-1", "INR", List.of(), T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lineItemRejectsNonPositiveQuantityAndPrice() {
        assertThatThrownBy(() -> new LineItem("S", "P", 0, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineItem("S", "P", 1, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
