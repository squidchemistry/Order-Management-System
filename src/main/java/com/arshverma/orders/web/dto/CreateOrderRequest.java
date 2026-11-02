package com.arshverma.orders.web.dto;

import com.arshverma.orders.domain.LineItem;
import com.arshverma.orders.service.CreateOrderCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** Totals are deliberately absent: the server computes them from the line items. */
public record CreateOrderRequest(
        @Schema(example = "cust-1001")
        @NotBlank @Size(max = 64) String customerId,

        @Schema(example = "INR", description = "ISO 4217 currency code")
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code, e.g. INR") String currency,

        @NotEmpty @Size(max = 50) List<@Valid @NotNull Item> items) {

    public record Item(
            @Schema(example = "SKU-KB-01")
            @NotBlank @Size(max = 64) String sku,

            @Schema(example = "Mechanical keyboard")
            @NotBlank @Size(max = 200) String productName,

            @Schema(example = "2")
            @NotNull @Positive @Max(1000) Integer quantity,

            @Schema(example = "3499.00")
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice) {
    }

    public CreateOrderCommand toCommand() {
        return new CreateOrderCommand(customerId, currency, items.stream()
                .map(i -> new LineItem(i.sku(), i.productName(), i.quantity(), i.unitPrice()))
                .toList());
    }
}
