package com.xuntian.mock.runtime.web;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;

@RestControllerAdvice
public final class RuntimeExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<RuntimeErrorResponse> platformFailure(
            PlatformException failure,
            ServerWebExchange exchange) {
        return failure(failure.errorCode(), failure.getMessage(), exchange);
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<RuntimeErrorResponse> bodyTooLarge(
            DataBufferLimitException failure,
            ServerWebExchange exchange) {
        return failure(ErrorCode.MOCK_REQUEST_TOO_LARGE, "Request body exceeds 1MB", exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RuntimeErrorResponse> internalFailure(
            Exception failure,
            ServerWebExchange exchange) {
        return failure(ErrorCode.MOCK_INTERNAL_ERROR, "Mock Runtime request failed", exchange);
    }

    private ResponseEntity<RuntimeErrorResponse> failure(
            ErrorCode errorCode,
            String message,
            ServerWebExchange exchange) {
        String mockRequestId = attribute(exchange, MockRuntimeController.MOCK_REQUEST_ID_ATTRIBUTE, "mr-");
        String traceId = attribute(exchange, MockRuntimeController.TRACE_ID_ATTRIBUTE, "trace-");
        return ResponseEntity.status(errorCode.httpStatus())
                .header("X-Mock-Request-Id", mockRequestId)
                .header("X-Trace-Id", traceId)
                .body(new RuntimeErrorResponse(false, errorCode.name(), message, mockRequestId, traceId));
    }

    private String attribute(ServerWebExchange exchange, String name, String prefix) {
        Object value = exchange.getAttribute(name);
        return value instanceof String text ? text : prefix + UUID.randomUUID();
    }

    public record RuntimeErrorResponse(
            boolean success,
            String code,
            String message,
            String mockRequestId,
            String traceId) { }
}
