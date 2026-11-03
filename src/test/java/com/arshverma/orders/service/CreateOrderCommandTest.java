package com.arshverma.orders.service;

import com.arshverma.orders.domain.LineItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreateOrderCommandTest {

    private static CreateOrderCommand command(String price, int quantity) {
        return new CreateOrderCommand("cust-1", "INR",
                List.of(new LineItem("SKU-1", "Keyboard", quantity, new BigDecimal(price))));
    }

    @Test
    void sameContentGivesSameFingerprintRegardlessOfPriceScale() {
        assertThat(command("10.5", 1).fingerprint()).isEqualTo(command("10.50", 1).fingerprint());
    }

    @Test
    void differentContentGivesDifferentFingerprint() {
        assertThat(command("10.50", 1).fingerprint()).isNotEqualTo(command("10.50", 2).fingerprint());
        assertThat(command("10.50", 1).fingerprint()).isNotEqualTo(command("10.51", 1).fingerprint());
    }
}
