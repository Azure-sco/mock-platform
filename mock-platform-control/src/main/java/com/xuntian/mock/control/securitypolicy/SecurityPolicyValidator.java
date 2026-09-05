package com.xuntian.mock.control.securitypolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class SecurityPolicyValidator {

    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 100_000;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Set<String> FALLBACK_FAILURES = Set.of(
            "DNS_FAILURE", "CONNECTION_REFUSED", "CONNECT_TIMEOUT", "TLS_PRE_HANDSHAKE_FAILURE");
    private static final Set<String> BUILTIN_SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key",
            "x-app-secret", "signature", "x-signature", "x-third-party-signature");
    private final boolean allowLoopbackCallback;

    public SecurityPolicyValidator() {
        this(false);
    }

    @Autowired
    public SecurityPolicyValidator(
            @Value("${mock.callback.allow-loopback:false}") boolean allowLoopbackCallback) {
        this.allowLoopbackCallback = allowLoopbackCallback;
    }

    public void validate(SecurityPolicyType type, String scopeKey, JsonNode config) {
        validateForStorage(config);
        switch (type) {
            case APP_ACL -> validateAppAcl(scopeKey, config);
            case SDK_HEADER_FILTER -> validateHeaderFilter(scopeKey, config);
            case SDK_FALLBACK_REAL -> validateFallbackReal(scopeKey, config);
            case PROVIDER_ENVIRONMENT -> validateProviderEnvironment(config);
            case CALLBACK_ALLOWLIST -> validateCallbackAllowlist(config);
            case CALLBACK_SIGNATURE -> validateCallbackSignature(config);
        }
    }

    public void validateForStorage(JsonNode config) {
        if (config == null || !config.isObject()) {
            throw invalid("config must be a JSON object");
        }
        if (CanonicalJson.write(config).length > MAX_BYTES) {
            throw new PlatformException(ErrorCode.PAYLOAD_TOO_LARGE, "Security policy config exceeds 1 MB");
        }
        validateComplexity(config, 0, new int[]{0});
        rejectInlineCredentials(config);
    }

    private void validateAppAcl(String scopeKey, JsonNode config) {
        String environment = requiredText(config, "environment", 32).toUpperCase(Locale.ROOT);
        String appCode = requiredText(config, "appCode", 128);
        if (!scopeKey.equals(environment + ":" + appCode)) {
            throw invalid("APP_ACL scopeKey must equal environment:appCode");
        }
        JsonNode rules = config.get("rules");
        if (rules == null || !rules.isArray()) {
            throw invalid("APP_ACL rules must be an array containing the complete aggregate");
        }
        Set<String> scopes = new HashSet<>();
        for (JsonNode rule : rules) {
            if (!rule.isObject()) {
                throw invalid("APP_ACL rule must be an object");
            }
            String providerCode = requiredText(rule, "providerCode", 64);
            JsonNode apiCodes = optionalTextArray(rule, "apiCodes", 64);
            optionalTextArray(rule, "tenantCodes", 128);
            optionalTextArray(rule, "testAccounts", 128);
            String fingerprint = providerCode + ":" + (apiCodes == null ? "*" : apiCodes.toString());
            if (!scopes.add(fingerprint)) {
                throw invalid("APP_ACL contains a duplicate Provider/API rule");
            }
        }
    }

    private void validateHeaderFilter(String scopeKey, JsonNode config) {
        validateSdkScope(scopeKey);
        Set<String> allowed = validateHeaderArray(config, "allowedBusinessHeaders");
        if (allowed.stream().anyMatch(BUILTIN_SENSITIVE_HEADERS::contains)) {
            throw invalid("SDK_HEADER_FILTER cannot allow a platform-sensitive header");
        }
        validateHeaderArray(config, "additionalSensitiveHeaders");
    }

    private void validateFallbackReal(String scopeKey, JsonNode config) {
        validateSdkScope(scopeKey);
        String environment = requiredText(config, "environment", 32).toUpperCase(Locale.ROOT);
        if ("PROD".equals(environment) || "PRODUCTION".equals(environment)) {
            throw invalid("FALLBACK_REAL is forbidden in PROD");
        }
        if (!config.path("requireReplayableBody").isBoolean()
                || !config.path("requireReplayableBody").booleanValue()) {
            throw invalid("FALLBACK_REAL must require a replayable body");
        }
        JsonNode categories = config.get("allowedFailureCategories");
        if (categories == null || !categories.isArray() || categories.isEmpty()) {
            throw invalid("FALLBACK_REAL allowedFailureCategories must be a non-empty array");
        }
        for (JsonNode category : categories) {
            if (!category.isTextual() || !FALLBACK_FAILURES.contains(category.textValue())) {
                throw invalid("FALLBACK_REAL contains an unsafe failure category");
            }
        }
        validateHostArray(config, "allowedRealHosts");
    }

    private void validateSdkScope(String scopeKey) {
        if ("default".equals(scopeKey)) return;
        String[] values = scopeKey.split(":", -1);
        boolean provider = values.length == 2 && "provider".equals(values[0]);
        boolean api = values.length == 3 && "api".equals(values[0]);
        if ((!provider && !api) || values[1].isBlank() || values[1].contains(":")) {
            throw invalid("SDK policy scopeKey must be default, provider:<provider>, or api:<provider>:<api>");
        }
        if (api && (values[2].isBlank() || values[2].contains(":"))) {
            throw invalid("SDK policy api scope is invalid");
        }
    }

    private Set<String> validateHeaderArray(JsonNode config, String field) {
        JsonNode headers = config.get(field);
        if (headers == null || !headers.isArray()) {
            throw invalid("SDK_HEADER_FILTER " + field + " must be an array");
        }
        Set<String> normalized = new HashSet<>();
        for (JsonNode value : headers) {
            if (!value.isTextual() || !HEADER_NAME.matcher(value.textValue()).matches()
                    || !normalized.add(value.textValue().toLowerCase(Locale.ROOT))) {
                throw invalid("SDK_HEADER_FILTER contains an invalid or duplicate header name");
            }
        }
        return Set.copyOf(normalized);
    }

    private void validateHostArray(JsonNode config, String field) {
        JsonNode hosts = config.get(field);
        if (hosts == null || !hosts.isArray()) {
            throw invalid(field + " must be an array");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode value : hosts) {
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 253
                    || value.textValue().contains("/") || value.textValue().contains(":")
                    || value.textValue().contains("*") || value.textValue().contains("@")
                    || !unique.add(value.textValue().toLowerCase(Locale.ROOT))) {
                throw invalid(field + " contains an invalid or duplicate host");
            }
        }
    }

    private void validateProviderEnvironment(JsonNode config) {
        requiredText(config, "providerCode", 64);
        requiredText(config, "environment", 32);
        requireHttpsArray(config, "baseUrls");
    }

    private void validateCallbackAllowlist(JsonNode config) {
        requireUrlArray(config, "allowedBaseUrls", allowLoopbackCallback);
    }

    private void validateCallbackSignature(JsonNode config) {
        requiredText(config, "secretRef", 256);
        String algorithm = requiredText(config, "algorithm", 64).toUpperCase(Locale.ROOT);
        if (!Set.of("HMAC_SHA256", "HMAC_SHA512").contains(algorithm)) {
            throw invalid("Callback signature algorithm is not allowed");
        }
    }

    private JsonNode optionalTextArray(JsonNode object, String field, int maxLength) {
        JsonNode values = object.get(field);
        if (values == null || values.isNull()) {
            return null;
        }
        if (!values.isArray()) {
            throw invalid(field + " must be an array");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength) {
                throw invalid(field + " contains an invalid value");
            }
            if (!unique.add(value.textValue())) {
                throw invalid(field + " contains a duplicate value");
            }
        }
        return values;
    }

    private void requireHttpsArray(JsonNode object, String field) {
        requireUrlArray(object, field, false);
    }

    private void requireUrlArray(JsonNode object, String field, boolean allowHttpLoopback) {
        JsonNode values = object.get(field);
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw invalid(field + " must be a non-empty array");
        }
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid(field + " contains an invalid URL");
            }
            try {
                URI uri = URI.create(value.textValue());
                boolean loopback = allowHttpLoopback && "http".equalsIgnoreCase(uri.getScheme())
                        && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
                if (!("https".equalsIgnoreCase(uri.getScheme()) || loopback) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getFragment() != null) {
                    throw invalid(field + " only accepts HTTPS URLs (or explicitly enabled local loopback HTTP) without user-info or fragments");
                }
            } catch (IllegalArgumentException failure) {
                throw invalid(field + " contains an invalid URL");
            }
        }
    }

    private String requiredText(JsonNode object, String field, int maxLength) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().trim().length() > maxLength) {
            throw invalid(field + " is invalid");
        }
        return value.textValue().trim();
    }

    private void validateComplexity(JsonNode node, int depth, int[] nodes) {
        nodes[0]++;
        if (depth > MAX_DEPTH || nodes[0] > MAX_NODES) {
            throw invalid("Security policy config exceeds complexity limits");
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                validateComplexity(child, depth + 1, nodes);
            }
        }
    }

    private void rejectInlineCredentials(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey().toLowerCase(Locale.ROOT);
                if (Set.of("password", "secretvalue", "tokenvalue", "authorizationvalue", "credentials")
                        .contains(name)) {
                    throw invalid("Inline credentials are forbidden; use an approved secretRef");
                }
                rejectInlineCredentials(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(this::rejectInlineCredentials);
        }
    }

    private PlatformException invalid(String message) {
        return new PlatformException(ErrorCode.INVALID_REQUEST, message);
    }
}
