package com.arshverma.orders.service;

import com.arshverma.orders.web.dto.OrderResponse;

/** @param replayed true when the order already existed for the given Idempotency-Key */
public record CreateOrderResult(OrderResponse order, boolean replayed) {
}
