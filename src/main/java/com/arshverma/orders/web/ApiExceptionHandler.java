package com.arshverma.orders.web;

import com.arshverma.orders.domain.InvalidOrderStateException;
import com.arshverma.orders.domain.OrderNotFoundException;
import com.arshverma.orders.service.IdempotencyKeyReusedException;
import com.arshverma.orders.service.VersionMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Maps every error to an RFC 9457 (formerly RFC 7807) application/problem+json body. Spring MVC's
 * own exceptions (malformed JSON, type mismatches, 405, ...) are handled by the base class.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static URI type(String slug) {
        return URI.create("/problems/" + slug);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Order not found", e.getMessage(),
                "order-not-found", request);
        problem.setProperty("orderId", e.getOrderId());
        return problem;
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    ProblemDetail invalidTransition(InvalidOrderStateException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Invalid order state transition", e.getMessage(),
                "invalid-state-transition", request);
        problem.setProperty("orderId", e.getOrderId());
        problem.setProperty("currentStatus", e.getCurrentStatus());
        problem.setProperty("targetStatus", e.getTargetStatus());
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentModification(ObjectOptimisticLockingFailureException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The order was modified by another request at the same time. Re-read it and retry if the "
                        + "action still makes sense.",
                "concurrent-modification", request);
        if (e.getIdentifier() != null) {
            problem.setProperty("orderId", e.getIdentifier());
        }
        return problem;
    }

    @ExceptionHandler(VersionMismatchException.class)
    ProblemDetail versionMismatch(VersionMismatchException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.PRECONDITION_FAILED, "Stale version", e.getMessage(),
                "stale-version", request);
        problem.setProperty("orderId", e.getOrderId());
        problem.setProperty("expectedVersion", e.getExpectedVersion());
        problem.setProperty("currentVersion", e.getActualVersion());
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail idempotencyKeyReused(IdempotencyKeyReusedException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key reused",
                e.getMessage() + ". Use a new key for a new order.", "idempotency-key-reused", request);
        problem.setProperty("existingOrderId", e.getExistingOrderId());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), "bad-request", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "Something went wrong on our side.", "internal-error", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, Object>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::fieldError)
                .toList();
        return validationFailed(errors, ex, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, Object>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.<String, Object>of(
                                "field", result.getMethodParameter().getParameterName(),
                                "message", String.valueOf(error.getDefaultMessage()))))
                .toList();
        return validationFailed(errors, ex, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getInstance() == null
                && request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationFailed(List<Map<String, Object>> errors, Exception ex,
            HttpHeaders headers, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "%d field(s) failed validation".formatted(errors.size()));
        problem.setTitle("Validation failed");
        problem.setType(type("validation-failed"));
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static Map<String, Object> fieldError(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", String.valueOf(error.getDefaultMessage()),
                "rejectedValue", String.valueOf(error.getRejectedValue()));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String slug,
                                         HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(type(slug));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
