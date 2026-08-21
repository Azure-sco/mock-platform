package com.xuntian.mock.client.core.failure;

import java.io.IOException;

public final class MockRuntimeUnavailableException extends IOException {

    private final String mockRequestId;

    public MockRuntimeUnavailableException(String mockRequestId, Throwable cause) {
        super("Mock Runtime unavailable, mockRequestId=" + mockRequestId, cause);
        this.mockRequestId = mockRequestId;
    }

    public String mockRequestId() {
        return mockRequestId;
    }
}
