package com.arshverma.orders.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record CancelOrderRequest(
        @Schema(example = "Customer changed their mind")
        @Size(max = 255) String reason) {
}
