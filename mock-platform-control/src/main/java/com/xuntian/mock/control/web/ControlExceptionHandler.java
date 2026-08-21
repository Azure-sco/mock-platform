package com.xuntian.mock.control.web;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ControlExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlExceptionHandler.class);

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Void>> platformFailure(
            PlatformException failure,
            HttpServletRequest request) {
        return failure(failure.errorCode(), failure.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpectedFailure(
            Exception failure,
            HttpServletRequest request) {
        String requestId = PlatformController.requestId(request);
        LOGGER.error("Unhandled control failure requestId={} type={}", requestId, failure.getClass().getName());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.failure(ErrorCode.INTERNAL_ERROR, "Internal server error", requestId));
    }

    private ResponseEntity<ApiResponse<Void>> failure(
            ErrorCode errorCode,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode, message, PlatformController.requestId(request)));
    }
}
