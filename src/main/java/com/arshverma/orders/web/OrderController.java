package com.arshverma.orders.web;

import com.arshverma.orders.domain.OrderStatus;
import com.arshverma.orders.service.CreateOrderResult;
import com.arshverma.orders.service.OrderService;
import com.arshverma.orders.web.dto.CancelOrderRequest;
import com.arshverma.orders.web.dto.CreateOrderRequest;
import com.arshverma.orders.web.dto.OrderResponse;
import com.arshverma.orders.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Create, read, list, confirm and cancel orders")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    private static final Set<String> SORTABLE = Set.of("id", "createdAt", "updatedAt", "totalAmount", "status",
            "customerId");

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Create an order", description = """
            Totals are computed server-side. Send an Idempotency-Key header to make retries safe: \
            a repeat of the same request returns the original order (200, Idempotent-Replayed: true); \
            the same key with a different body is rejected with 422.""")
    @ApiResponse(responseCode = "201", description = "Order created",
            headers = @Header(name = "Location", description = "URL of the new order"))
    @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different request body")
    public ResponseEntity<OrderResponse> create(
            @Parameter(description = "Client-generated unique key (e.g. a UUID), at most 255 characters")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        String key = normaliseIdempotencyKey(idempotencyKey);
        CreateOrderResult result = orderService.create(request.toCommand(), key);
        OrderResponse order = result.order();
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(order.id()).toUri();
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(location)
                .eTag(etag(order))
                .header(IDEMPOTENT_REPLAYED, String.valueOf(result.replayed()))
                .body(order);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order by id", description = "The ETag header carries the order version.")
    @ApiResponse(responseCode = "200", description = "Found")
    @ApiResponse(responseCode = "404", description = "No such order")
    public ResponseEntity<OrderResponse> get(@PathVariable Long id) {
        OrderResponse order = orderService.get(id);
        return ResponseEntity.ok().eTag(etag(order)).body(order);
    }

    @GetMapping
    @Operation(summary = "List orders", description = """
            Paginated (default size 20, max 100), newest first. Optional filters: status, customerId. \
            Sortable by: id, createdAt, updatedAt, totalAmount, status, customerId.""")
    public PageResponse<OrderResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String customerId,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new IllegalArgumentException("Cannot sort by '%s'; allowed: %s"
                        .formatted(order.getProperty(), SORTABLE));
            }
        }
        return orderService.list(status, customerId, pageable);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm a PENDING order")
    @ApiResponse(responseCode = "200", description = "Confirmed")
    @ApiResponse(responseCode = "404", description = "No such order")
    @ApiResponse(responseCode = "409", description = "Not PENDING, or modified concurrently")
    @ApiResponse(responseCode = "412", description = "If-Match version is stale")
    public ResponseEntity<OrderResponse> confirm(
            @PathVariable Long id,
            @Parameter(description = "Optional ETag from a previous read, e.g. \"0\"")
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        OrderResponse order = orderService.confirm(id, parseIfMatch(ifMatch));
        return ResponseEntity.ok().eTag(etag(order)).body(order);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a PENDING or CONFIRMED order")
    @ApiResponse(responseCode = "200", description = "Cancelled")
    @ApiResponse(responseCode = "404", description = "No such order")
    @ApiResponse(responseCode = "409", description = "Already cancelled, or modified concurrently")
    @ApiResponse(responseCode = "412", description = "If-Match version is stale")
    public ResponseEntity<OrderResponse> cancel(
            @PathVariable Long id,
            @Parameter(description = "Optional ETag from a previous read, e.g. \"1\"")
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody(required = false) CancelOrderRequest request) {
        String reason = request == null ? null : request.reason();
        OrderResponse order = orderService.cancel(id, reason, parseIfMatch(ifMatch));
        return ResponseEntity.ok().eTag(etag(order)).body(order);
    }

    private static String etag(OrderResponse order) {
        return "\"" + order.version() + "\"";
    }

    /** Accepts "3", "\"3\"" or "W/\"3\""; "*" or absent means no precondition. */
    static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be an ETag returned by this API, e.g. \"0\"");
        }
    }

    private static String normaliseIdempotencyKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new IllegalArgumentException(IDEMPOTENCY_KEY + " must be 1-255 characters");
        }
        return trimmed;
    }
}
