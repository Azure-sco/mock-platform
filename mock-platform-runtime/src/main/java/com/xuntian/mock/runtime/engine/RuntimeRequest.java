package com.xuntian.mock.runtime.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RuntimeRequest {

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-signature", "x-app-secret", "x-api-key");

    private final String environment;
    private final String app;
    private final String tenant;
    private final String testAccount;
    private final String provider;
    private final String api;
    private final String method;
    private final String rawPath;
    private final String contentType;
    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> query;
    private final byte[] body;
    private final String mockRequestId;
    private final String traceId;

    public RuntimeRequest(
            String environment,
            String app,
            String tenant,
            String testAccount,
            String provider,
            String api,
            String method,
            String rawPath,
            String contentType,
            Map<String, List<String>> headers,
            Map<String, List<String>> query,
            byte[] body,
            String mockRequestId,
            String traceId) {
        this.environment = environment;
        this.app = app;
        this.tenant = tenant;
        this.testAccount = testAccount;
        this.provider = provider;
        this.api = api;
        this.method = method;
        this.rawPath = rawPath;
        this.contentType = contentType;
        this.headers = copyHeaders(headers);
        this.query = copyMultiMap(query);
        this.body = body == null ? new byte[0] : body.clone();
        this.mockRequestId = mockRequestId;
        this.traceId = traceId;
    }

    public String environment() { return environment; }
    public String app() { return app; }
    public String tenant() { return tenant; }
    public String testAccount() { return testAccount; }
    public String provider() { return provider; }
    public String api() { return api; }
    public String method() { return method; }
    public String rawPath() { return rawPath; }
    public String contentType() { return contentType; }
    public Map<String, List<String>> headers() { return headers; }
    public Map<String, List<String>> query() { return query; }
    public byte[] body() { return body.clone(); }
    public int bodyLength() { return body.length; }
    public String mockRequestId() { return mockRequestId; }
    public String traceId() { return traceId; }

    public Optional<String> firstHeader(String name) {
        if (isSensitiveHeader(name)) {
            return Optional.empty();
        }
        String expected = name.toLowerCase(Locale.ROOT);
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(expected))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }

    public Optional<String> firstQuery(String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

    private static Map<String, List<String>> copyMultiMap(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, values) -> result.put(
                    key, values == null ? List.of() : List.copyOf(new ArrayList<>(values))));
        }
        return Map.copyOf(result);
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, values) -> {
                if (!isSensitiveHeader(key)) {
                    result.put(key, values == null ? List.of() : List.copyOf(new ArrayList<>(values)));
                }
            });
        }
        return Map.copyOf(result);
    }

    public static boolean isSensitiveHeader(String name) {
        if (name == null) {
            return true;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return SENSITIVE_HEADERS.contains(normalized)
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.endsWith("-signature");
    }
}
