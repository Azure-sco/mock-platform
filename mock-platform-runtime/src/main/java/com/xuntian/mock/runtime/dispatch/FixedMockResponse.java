package com.xuntian.mock.runtime.dispatch;

public record FixedMockResponse(int status, String contentType, Object body) {
}
