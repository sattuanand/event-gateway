package com.eventledger.gateway.api;

import com.eventledger.gateway.api.dto.ErrorResponse;
import com.eventledger.gateway.client.AccountServiceRejectedException;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.service.EventNotFoundException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Tracer tracer;

    public GlobalExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    /** Bean Validation failures: missing fields, amount <= 0, bad currency. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .sorted()
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed for the submitted event", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        List<String> details = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted()
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", details);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST,
                "Missing required query parameter '" + e.getParameterName() + "'", null);
    }

    /**
     * Unparseable body. The common real case is an unknown "type" — Jackson cannot bind it to the
     * enum, so it never reaches Bean Validation. Unwrapped here so the client is told which values
     * are legal instead of getting a generic 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof InvalidFormatException ife && ife.getTargetType() != null
                && ife.getTargetType().isEnum()) {
            String field = ife.getPath().isEmpty() ? "field"
                    : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            String allowed = String.join(", ",
                    java.util.Arrays.stream(ife.getTargetType().getEnumConstants())
                            .map(Object::toString).toList());
            return build(HttpStatus.BAD_REQUEST,
                    "Invalid value for '" + field + "'",
                    List.of(field + ": '" + ife.getValue() + "' is not valid; allowed values are [" + allowed + "]"));
        }
        return build(HttpStatus.BAD_REQUEST, "Request body is malformed or not valid JSON", null);
    }

    /**
     * Without this, {@code @ExceptionHandler(Exception.class)} below would catch these two Spring
     * framework exceptions itself and turn a client mistake (wrong Content-Type, wrong HTTP verb)
     * into a misleading 500.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type '" + e.getContentType() + "' is not supported; use application/json", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED,
                "HTTP method '" + e.getMethod() + "' is not supported for this endpoint", null);
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EventNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), null);
    }

    /**
     * Requirement 6: the Account Service being unreachable is a 503, not a 500 and not a hang.
     * The event itself is already durably stored; this response is a "retry later" signal, and the
     * eventId-based idempotency is what makes acting on that signal safe.
     */
    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUnavailable(AccountServiceUnavailableException e) {
        log.warn("Returning 503 — account-service unavailable: {}", e.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "Account Service is unavailable. The event has been stored and can be safely resubmitted "
                        + "with the same eventId once the service recovers.",
                null);
    }

    /** Account Service answered 4xx — it is up, and it refused us. Our bug, not an outage. */
    @ExceptionHandler(AccountServiceRejectedException.class)
    public ResponseEntity<ErrorResponse> handleRejected(AccountServiceRejectedException e) {
        return build(HttpStatus.BAD_GATEWAY,
                "Account Service rejected the transaction (downstream status "
                        + e.getDownstreamStatus() + ")", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal error occurred", null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, List<String> details) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message, details, currentTraceId());
        return ResponseEntity.status(status).body(body);
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
