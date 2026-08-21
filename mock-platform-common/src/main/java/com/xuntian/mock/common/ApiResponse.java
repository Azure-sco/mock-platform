package com.xuntian.mock.common;

public final class ApiResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;
    private final String requestId;

    private ApiResponse(boolean success, String code, String message, T data, String requestId) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
        this.requestId = requestId;
    }

    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(true, "OK", "success", data, requestId);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message, String requestId) {
        return new ApiResponse<>(false, errorCode.name(), message, null, requestId);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getRequestId() {
        return requestId;
    }
}
