package com.xuntian.mock.runtime.web;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import java.util.Arrays;
import java.util.UUID;

@RestControllerAdvice
public final class RuntimeExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeExceptionHandler.class);

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<RuntimeErrorResponse> platformFailure(
            PlatformException failure,
            ServerWebExchange exchange) {
        RequestContext context = context(exchange);
        LOG.warn(
                "Runtime request failed mockRequestId={} traceId={} method={} path={} code={}",
                context.mockRequestId(), context.traceId(), exchange.getRequest().getMethod().name(),
                exchange.getRequest().getURI().getRawPath(), failure.errorCode().name());
        return failure(failure.errorCode(), failure.getMessage(), exchange);
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<RuntimeErrorResponse> bodyTooLarge(
            DataBufferLimitException failure,
            ServerWebExchange exchange) {
        RequestContext context = context(exchange);
        LOG.warn(
                "Runtime request rejected mockRequestId={} traceId={} method={} path={} code={}",
                context.mockRequestId(), context.traceId(), exchange.getRequest().getMethod().name(),
                exchange.getRequest().getURI().getRawPath(), ErrorCode.MOCK_REQUEST_TOO_LARGE.name());
        return failure(ErrorCode.MOCK_REQUEST_TOO_LARGE, "Request body exceeds 1MB", exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RuntimeErrorResponse> internalFailure(
            Exception failure,
            ServerWebExchange exchange) {
        RequestContext context = context(exchange);
        LOG.error(
                "Unhandled Runtime failure mockRequestId={} traceId={} method={} path={} type={} "
                        + "rootCauseType={} stack={}",
                context.mockRequestId(), context.traceId(), exchange.getRequest().getMethod().name(),
                exchange.getRequest().getURI().getRawPath(), failure.getClass().getName(), rootCauseType(failure),
                stackSummary(failure));
        return failure(ErrorCode.MOCK_INTERNAL_ERROR, "Mock Runtime request failed", exchange);
    }

    private ResponseEntity<RuntimeErrorResponse> failure(
            ErrorCode errorCode,
            String message,
            ServerWebExchange exchange) {
        RequestContext context = context(exchange);
        return ResponseEntity.status(errorCode.httpStatus())
                .header("X-Mock-Request-Id", context.mockRequestId())
                .header("X-Trace-Id", context.traceId())
                .body(new RuntimeErrorResponse(
                        false, errorCode.name(), message, context.mockRequestId(), context.traceId()));
    }

    private RequestContext context(ServerWebExchange exchange) {
        return new RequestContext(
                attribute(exchange, MockRuntimeController.MOCK_REQUEST_ID_ATTRIBUTE, "mr-"),
                attribute(exchange, MockRuntimeController.TRACE_ID_ATTRIBUTE, "trace-"));
    }

    private String attribute(ServerWebExchange exchange, String name, String prefix) {
        Object value = exchange.getAttribute(name);
        if (value instanceof String text) {
            return text;
        }
        String generated = prefix + UUID.randomUUID();
        exchange.getAttributes().put(name, generated);
        return generated;
    }

    private record RequestContext(String mockRequestId, String traceId) { }

    private static String rootCauseType(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }

    private static String stackSummary(Throwable failure) {
        StackTraceElement[] stack = failure.getStackTrace();
        return Arrays.toString(Arrays.copyOf(stack, Math.min(stack.length, 32)));
    }

    public record RuntimeErrorResponse(
            boolean success,
            String code,
            String message,
            String mockRequestId,
            String traceId) { }
}
