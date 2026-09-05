package com.xuntian.mock.runtime.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.runtime.engine.CompiledJsonPath;
import com.xuntian.mock.runtime.engine.CompiledTemplate;
import com.xuntian.mock.runtime.engine.RuntimeRequest;
import com.xuntian.mock.runtime.snapshot.FixtureDefinition;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable callback configuration compiled from a published Snapshot. */
public final class CompiledCallbackDefinition {

    private static final long MAX_DELAY_MS = 86_400_000L;
    private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH");

    private final String callbackDefinitionId;
    private final boolean enabled;
    private final String triggerState;
    private final String urlSource;
    private final URI fixedUrl;
    private final CompiledJsonPath requestField;
    private final String method;
    private final Map<String, String> headers;
    private final CompiledTemplate payloadTemplate;
    private final long delayMs;
    private final int maxRetry;
    private final List<Long> retryIntervalsMs;
    private final int totalDeliveryCount;
    private final List<Long> deliveryOffsetsMs;
    private final long signaturePolicyVersionId;
    private final long allowlistPolicyVersionId;

    private CompiledCallbackDefinition(
            FixtureDefinition.CallbackDefinition source,
            String urlSource,
            URI fixedUrl,
            CompiledJsonPath requestField,
            String method,
            Map<String, String> headers,
            CompiledTemplate payloadTemplate) {
        this.callbackDefinitionId = source.callbackDefinitionId();
        this.enabled = source.enabled();
        this.triggerState = source.triggerState();
        this.urlSource = urlSource;
        this.fixedUrl = fixedUrl;
        this.requestField = requestField;
        this.method = method;
        this.headers = Map.copyOf(new LinkedHashMap<>(headers));
        this.payloadTemplate = payloadTemplate;
        this.delayMs = source.delayMs();
        this.maxRetry = source.maxRetry();
        this.retryIntervalsMs = List.copyOf(source.retryIntervalsMs());
        this.totalDeliveryCount = source.totalDeliveryCount();
        this.deliveryOffsetsMs = List.copyOf(source.deliveryOffsetsMs());
        this.signaturePolicyVersionId = source.signaturePolicyVersionId();
        this.allowlistPolicyVersionId = source.allowlistPolicyVersionId();
    }

    public static CompiledCallbackDefinition compile(
            FixtureDefinition.CallbackDefinition source,
            String environment,
            ObjectMapper mapper) {
        if (source == null) throw new IllegalArgumentException("Callback Definition is required");
        requireSafeId(source.callbackDefinitionId(), "callbackDefinitionId");
        requireSafeId(source.triggerState(), "callback.triggerState");
        String urlSource = requireText(source.urlSource(), "callback.urlSource").toUpperCase(Locale.ROOT);
        URI fixedUrl = null;
        CompiledJsonPath requestField = null;
        if ("FIXED".equals(urlSource)) {
            fixedUrl = validateFixedUrl(source.url(), environment);
        } else if ("REQUEST_FIELD".equals(urlSource)) {
            requestField = CompiledJsonPath.compile(source.requestField());
        } else {
            throw new IllegalArgumentException("Callback urlSource must be FIXED or REQUEST_FIELD");
        }
        String method = requireText(source.method(), "callback.method").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("Callback method must be POST, PUT or PATCH");
        }
        Map<String, String> headers = validateHeaders(source.headers());
        if (source.delayMs() < 0 || source.delayMs() > MAX_DELAY_MS) {
            throw new IllegalArgumentException("Callback delayMs must be from 0 to 86400000");
        }
        if (source.maxRetry() < 0 || source.maxRetry() > 3) {
            throw new IllegalArgumentException("Callback maxRetry must be from 0 to 3");
        }
        validateSchedule(source.retryIntervalsMs(), source.maxRetry(), false, "retryIntervalsMs");
        if (source.totalDeliveryCount() < 1 || source.totalDeliveryCount() > 3) {
            throw new IllegalArgumentException("Callback totalDeliveryCount must be from 1 to 3");
        }
        validateSchedule(source.deliveryOffsetsMs(), source.totalDeliveryCount(), true, "deliveryOffsetsMs");
        if (source.signaturePolicyVersionId() <= 0 || source.allowlistPolicyVersionId() <= 0) {
            throw new IllegalArgumentException("Callback security policy Version ids must be positive");
        }
        return new CompiledCallbackDefinition(
                source, urlSource, fixedUrl, requestField, method, headers,
                CompiledTemplate.compile(source.payloadTemplate(), Map.of(), mapper));
    }

    private static URI validateFixedUrl(String value, String environment) {
        try {
            URI uri = URI.create(requireText(value, "callback.url"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean localTestHttp = !"PROD".equalsIgnoreCase(environment) && "http".equals(scheme)
                    && ("localhost".equals(host) || "127.0.0.1".equals(host));
            if (!("https".equals(scheme) || localTestHttp) || host.isBlank()
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Callback URL must use HTTPS (loopback HTTP is TEST-only)");
            }
            return uri;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Callback URL is invalid", failure);
        }
    }

    private static Map<String, String> validateHeaders(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source == null) return result;
        source.forEach((name, value) -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (name == null || name.isBlank() || value == null || hasCrLf(name) || hasCrLf(value)
                    || "content-length".equals(lower) || lower.startsWith("x-mock-")
                    || RuntimeRequest.isSensitiveHeader(lower)) {
                throw new IllegalArgumentException("Unsafe Callback header: " + name);
            }
            result.put(name, value);
        });
        return result;
    }

    private static void validateSchedule(
            List<Long> values,
            int expectedSize,
            boolean requireSorted,
            String name) {
        if (values == null || values.size() != expectedSize) {
            throw new IllegalArgumentException("Callback " + name + " length does not match its count");
        }
        long total = 0;
        long previous = -1;
        for (Long value : values) {
            if (value == null || value < 0 || value > MAX_DELAY_MS) {
                throw new IllegalArgumentException("Callback " + name + " contains an invalid delay");
            }
            if (requireSorted && value < previous) {
                throw new IllegalArgumentException("Callback " + name + " must be sorted");
            }
            total += value;
            previous = value;
        }
        if (total > MAX_DELAY_MS) {
            throw new IllegalArgumentException("Callback " + name + " must fit within 24h");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static void requireSafeId(String value, String name) {
        if (value == null || value.length() > 128 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static boolean hasCrLf(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    public String callbackDefinitionId() { return callbackDefinitionId; }
    public boolean enabled() { return enabled; }
    public String triggerState() { return triggerState; }
    public String urlSource() { return urlSource; }
    public URI fixedUrl() { return fixedUrl; }
    public CompiledJsonPath requestField() { return requestField; }
    public String method() { return method; }
    public Map<String, String> headers() { return headers; }
    public CompiledTemplate payloadTemplate() { return payloadTemplate; }
    public long delayMs() { return delayMs; }
    public int maxRetry() { return maxRetry; }
    public List<Long> retryIntervalsMs() { return retryIntervalsMs; }
    public int totalDeliveryCount() { return totalDeliveryCount; }
    public List<Long> deliveryOffsetsMs() { return deliveryOffsetsMs; }
    public long signaturePolicyVersionId() { return signaturePolicyVersionId; }
    public long allowlistPolicyVersionId() { return allowlistPolicyVersionId; }
}
