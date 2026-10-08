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
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Arrays;

@RestControllerAdvice
public final class ControlExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlExceptionHandler.class);

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Void>> platformFailure(
            PlatformException failure,
            HttpServletRequest request) {
        LOGGER.warn(
                "Control request failed requestId={} method={} path={} code={}",
                logRequestId(request), request.getMethod(), request.getRequestURI(),
                failure.errorCode().name());
        return failure(failure.errorCode(), failure.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> uploadTooLarge(
            MaxUploadSizeExceededException failure,
            HttpServletRequest request) {
        LOGGER.warn(
                "Control request rejected requestId={} method={} path={} code={}",
                logRequestId(request), request.getMethod(), request.getRequestURI(),
                ErrorCode.PAYLOAD_TOO_LARGE.name());
        return failure(ErrorCode.PAYLOAD_TOO_LARGE, "Contract file exceeds 5 MB", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpectedFailure(
            Exception failure,
            HttpServletRequest request) {
        String requestId = PlatformController.requestId(request);
        LOGGER.error(
                "Unhandled control failure requestId={} method={} path={} type={} rootCauseType={} stack={}",
                logRequestId(request), request.getMethod(), request.getRequestURI(), failure.getClass().getName(),
                rootCauseType(failure), stackSummary(failure));
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

    private static String logRequestId(HttpServletRequest request) {
        String value = PlatformController.requestId(request);
        String safe = value == null ? "" : value.replace('\r', '_').replace('\n', '_');
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

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
}
