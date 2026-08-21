package com.xuntian.mock.common;

public enum ErrorCode {
    INVALID_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    PAYLOAD_TOO_LARGE(413),
    MOCK_APP_UNAUTHORIZED(401),
    MOCK_NO_FIXED_RESPONSE(404),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
