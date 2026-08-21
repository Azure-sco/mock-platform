package com.xuntian.mock.client.core.security;

import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.routing.RouteConfig;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class MockHeaders {

    public static final String AUTHORIZATION = "Authorization";
    public static final String PROVIDER = "X-Mock-Provider";
    public static final String API = "X-Mock-Api";
    public static final String TENANT = "X-Mock-Tenant";
    public static final String TEST_ACCOUNT = "X-Mock-Test-Account";
    public static final String TRACE_ID = "X-Mock-Trace-Id";
    public static final String BUSINESS_NO = "X-Mock-Business-No";
    public static final String REQUEST_ID = "X-Mock-Request-Id";

    private MockHeaders() {
    }

    public static Map<String, List<String>> build(
            Map<String, ? extends Collection<String>> original,
            MockContext context,
            RouteConfig routeConfig,
            String mockAppToken) {
        Map<String, List<String>> headers = HeaderSanitizer.sanitize(
                original,
                routeConfig.allowedBusinessHeaders(),
                routeConfig.additionalSensitiveHeaders());

        putIfPresent(headers, AUTHORIZATION, prefixToken(mockAppToken));
        putIfPresent(headers, PROVIDER, context.provider());
        putIfPresent(headers, API, context.api());
        putIfPresent(headers, TENANT, context.tenant());
        putIfPresent(headers, TEST_ACCOUNT, context.testAccount());
        putIfPresent(headers, TRACE_ID, context.traceId());
        putIfPresent(headers, BUSINESS_NO, context.businessNo());
        putIfPresent(headers, REQUEST_ID, context.mockRequestId());
        return headers;
    }

    private static String prefixToken(String token) {
        return isPresent(token) ? "MockApp " + token : null;
    }

    private static void putIfPresent(Map<String, List<String>> headers, String name, String value) {
        removeIgnoreCase(headers, name);
        if (isPresent(value)) {
            headers.put(name, Collections.singletonList(value));
        }
    }

    private static void removeIgnoreCase(Map<String, List<String>> headers, String name) {
        Iterator<String> iterator = headers.keySet().iterator();
        while (iterator.hasNext()) {
            if (name.equalsIgnoreCase(iterator.next())) {
                iterator.remove();
            }
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
