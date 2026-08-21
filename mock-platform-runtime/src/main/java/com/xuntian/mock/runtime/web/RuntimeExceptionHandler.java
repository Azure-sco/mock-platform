package com.xuntian.mock.runtime.web;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.common.RequestIds;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

@RestControllerAdvice
public final class RuntimeExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Void>> platformFailure(
            PlatformException failure,
            ServerWebExchange exchange) {
        return failure(failure.errorCode(), failure.getMessage(), exchange);
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<ApiResponse<Void>> bodyTooLarge(
            DataBufferLimitException failure,
            ServerWebExchange exchange) {
        return failure(ErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds 1MB", exchange);
    }

    private ResponseEntity<ApiResponse<Void>> failure(
            ErrorCode errorCode,
            String message,
            ServerWebExchange exchange) {
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Mock-Request-Id");
        if (requestId == null) {
            requestId = RequestIds.generate();
        }
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode, message, requestId));
    }
}
