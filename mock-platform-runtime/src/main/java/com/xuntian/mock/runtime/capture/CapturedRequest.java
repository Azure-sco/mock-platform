package com.xuntian.mock.runtime.capture;

import java.util.Set;

public record CapturedRequest(
        String appCode,
        String environment,
        String method,
        String path,
        String rawQuery,
        String provider,
        String api,
        String requestId,
        int bodyBytes,
        String authorizationScheme,
        Set<String> headerNames) {
}
