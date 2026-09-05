package com.xuntian.mock.control.web;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public final class AdminWriteRequestGuard {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    public String requireIdempotencyKey(HttpServletRequest request) {
        String value = request.getHeader(IDEMPOTENCY_KEY);
        if (value == null || value.isBlank() || value.trim().length() > 128) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Idempotency-Key is required and must be <= 128 characters");
        }
        return value.trim();
    }
}
