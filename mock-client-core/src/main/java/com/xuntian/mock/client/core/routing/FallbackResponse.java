package com.xuntian.mock.client.core.routing;

import java.util.Objects;

public final class FallbackResponse {

    private final int status;
    private final String contentType;
    private final String body;

    public FallbackResponse(int status, String contentType, String body) {
        this.status = status;
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.body = Objects.requireNonNull(body, "body");
    }

    public int status() {
        return status;
    }

    public String contentType() {
        return contentType;
    }

    public String body() {
        return body;
    }
}
