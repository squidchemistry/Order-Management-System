package com.arshverma.orders.web;

import com.arshverma.orders.domain.InvalidOrderStateException;
import com.arshverma.orders.domain.Order;
import com.arshverma.orders.domain.OrderNotFoundException;
import com.arshverma.orders.domain.OrderStatus;
import com.arshverma.orders.service.CreateOrderCommand;
import com.arshverma.orders.service.CreateOrderResult;
import com.arshverma.orders.service.IdempotencyKeyReusedException;
import com.arshverma.orders.service.OrderService;
import com.arshverma.orders.service.VersionMismatchException;
import com.arshverma.orders.web.dto.OrderResponse;
import com.arshverma.orders.web.dto.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String VALID_BODY = """
            {"customerId":"cust-1","currency":"INR",
             "items":[{"sku":"SKU-1","productName":"Keyboard","quantity":2,"unitPrice":10.50}]}""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService orderService;

    private static OrderResponse order(long id, OrderStatus status, long version) {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        return new OrderResponse(id, "cust-1", status, "INR", new BigDecimal("21.00"),
                List.of(new OrderResponse.Line(1, "SKU-1", "Keyboard", 2, new BigDecimal("10.50"),
                        new BigDecimal("21.00"))),
                null, now, now, null, null, version);
    }

    @Test
    void createReturns201WithLocationAndEtag() throws Exception {
        when(orderService.create(any(), isNull())).thenReturn(new CreateOrderResult(order(7, OrderStatus.PENDING, 0), false));

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/orders/7"))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(21.00));

        ArgumentCaptor<CreateOrderCommand> captor = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(orderService).create(captor.capture(), isNull());
        assertThat(captor.getValue().items()).singleElement()
                .satisfies(item -> assertThat(item.unitPrice()).isEqualByComparingTo("10.50"));
    }

    @Test
    void replayWithSameIdempotencyKeyReturns200() throws Exception {
        when(orderService.create(any(), eq("key-1"))).thenReturn(new CreateOrderResult(order(7, OrderStatus.PENDING, 0), true));

        mvc.perform(post("/api/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void idempotencyKeyReusedWithDifferentBodyReturns422() throws Exception {
        when(orderService.create(any(), eq("key-1"))).thenThrow(new IdempotencyKeyReusedException("key-1", 7L));

        mvc.perform(post("/api/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"))
                .andExpect(jsonPath("$.existingOrderId").value(7));
    }

    @Test
    void blankIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/orders").header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest());
        verify(orderService, never()).create(any(), anyString());
    }

    @Test
    void invalidBodyReturns400ProblemWithEveryFieldError() throws Exception {
        String body = """
                {"customerId":"","currency":"inr",
                 "items":[{"sku":"SKU-1","productName":"Keyboard","quantity":0,"unitPrice":-1}]}""";

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"))
                .andExpect(jsonPath("$.instance").value("/api/orders"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder(
                        "customerId", "currency", "items[0].quantity", "items[0].unitPrice")));
        verify(orderService, never()).create(any(), any());
    }

    @Test
    void emptyItemsAndMalformedJsonAreRejected() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c\",\"currency\":\"INR\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items"));

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void getReturnsOrderWithEtag() throws Exception {
        when(orderService.get(7L)).thenReturn(order(7, OrderStatus.CONFIRMED, 1));

        mvc.perform(get("/api/orders/7"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.items[0].lineTotal").value(21.00));
    }

    @Test
    void unknownOrderReturns404Problem() throws Exception {
        when(orderService.get(99L)).thenThrow(new OrderNotFoundException(99L));

        mvc.perform(get("/api/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/order-not-found"))
                .andExpect(jsonPath("$.orderId").value(99));
    }

    @Test
    void nonNumericIdReturns400() throws Exception {
        mvc.perform(get("/api/orders/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void listPassesFiltersAndPaging() throws Exception {
        when(orderService.list(eq(OrderStatus.PENDING), eq("cust-1"), any()))
                .thenReturn(new PageResponse<>(List.of(order(7, OrderStatus.PENDING, 0)), 1, 5, 6, 2, false));

        mvc.perform(get("/api/orders").param("status", "PENDING").param("customerId", "cust-1")
                        .param("page", "1").param("size", "5").param("sort", "totalAmount,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.page").value(1));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).list(eq(OrderStatus.PENDING), eq("cust-1"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.asc("totalAmount"));
    }

    @Test
    void listDefaultsToNewestFirstAndCapsPageSize() throws Exception {
        when(orderService.list(any(), any(), any())).thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0, false));

        mvc.perform(get("/api/orders").param("size", "5000")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).list(isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"));
    }

    @Test
    void listRejectsUnknownStatusAndSortField() throws Exception {
        mvc.perform(get("/api/orders").param("status", "SHIPPED")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("sort", "password")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/bad-request"));
    }

    @Test
    void confirmPassesIfMatchVersion() throws Exception {
        when(orderService.confirm(7L, 0L)).thenReturn(order(7, OrderStatus.CONFIRMED, 1));

        mvc.perform(post("/api/orders/7/confirm").header("If-Match", "\"0\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void illegalTransitionReturns409Problem() throws Exception {
        when(orderService.confirm(7L, null))
                .thenThrow(new InvalidOrderStateException(7L, OrderStatus.CANCELLED, OrderStatus.CONFIRMED));

        mvc.perform(post("/api/orders/7/confirm"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("/problems/invalid-state-transition"))
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.targetStatus").value("CONFIRMED"));
    }

    @Test
    void optimisticLockFailureReturns409Problem() throws Exception {
        when(orderService.confirm(7L, null))
                .thenThrow(new ObjectOptimisticLockingFailureException(Order.class, 7L));

        mvc.perform(post("/api/orders/7/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/concurrent-modification"))
                .andExpect(jsonPath("$.title").value("Concurrent modification"))
                .andExpect(jsonPath("$.orderId").value(7));
    }

    @Test
    void staleIfMatchReturns412Problem() throws Exception {
        when(orderService.cancel(7L, null, 0L)).thenThrow(new VersionMismatchException(7L, 0, 1));

        mvc.perform(post("/api/orders/7/cancel").header("If-Match", "W/\"0\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.currentVersion").value(1));
    }

    @Test
    void cancelWithReasonAndWithoutBody() throws Exception {
        when(orderService.cancel(7L, "too slow", null)).thenReturn(order(7, OrderStatus.CANCELLED, 1));
        when(orderService.cancel(8L, null, null)).thenReturn(order(8, OrderStatus.CANCELLED, 1));

        mvc.perform(post("/api/orders/7/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too slow\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/orders/8/cancel")).andExpect(status().isOk());

        verify(orderService).cancel(7L, "too slow", null);
        verify(orderService).cancel(8L, null, null);
    }

    @Test
    void cancelReasonTooLongIsRejected() throws Exception {
        mvc.perform(post("/api/orders/7/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
    }
}
