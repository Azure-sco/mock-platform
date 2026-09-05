package com.xuntian.mock.control.sdkconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class SdkConfigValidator {

    private static final int MAX_BYTES = 1_048_576;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern HEADER = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Set<String> BUILTIN_SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key",
            "x-app-secret", "signature", "x-signature", "x-third-party-signature");
    private static final Set<String> MODES = Set.of("REAL", "MOCK", "CANARY");
    private static final Set<String> UNAVAILABLE_POLICIES =
            Set.of("FAST_FAIL", "FALLBACK_REAL", "FALLBACK_RESPONSE");

    public void validate(
            String appCode,
            String environment,
            JsonNode routing,
            List<PolicyBundle> policies,
            Instant effectiveAt,
            Instant expireAt) {
        if (appCode == null || appCode.isBlank()) throw invalid("appCode is invalid");
        if (routing == null || !routing.isObject() || CanonicalJson.write(routing).length > MAX_BYTES) {
            throw invalid("routing must be a JSON object <= 1 MB");
        }
        if (effectiveAt == null || expireAt != null && !expireAt.isAfter(effectiveAt)) {
            throw invalid("expireAt must be after effectiveAt");
        }
        JsonNode defaultRoute = routing.get("defaultRoute");
        JsonNode providerRoutes = routing.get("providerRoutes");
        JsonNode apiRoutes = routing.get("apiRoutes");
        if (defaultRoute == null || !defaultRoute.isObject()
                || providerRoutes == null || !providerRoutes.isObject()
                || apiRoutes == null || !apiRoutes.isObject()) {
            throw invalid("routing requires defaultRoute, providerRoutes and apiRoutes objects");
        }
        validateRuntimeBaseUri(routing.get("runtimeBaseUri"), environment);
        if (!routing.path("allowRequestOverride").isBoolean()) {
            throw invalid("routing.allowRequestOverride must be a boolean");
        }
        Map<Long, PolicyBundle> byId = new HashMap<>();
        for (PolicyBundle policy : policies) {
            if (byId.put(policy.policyVersionId(), policy) != null) {
                throw invalid("Duplicate security policy reference");
            }
            String payloadChecksum = Checksum.sha256Hex(CanonicalJson.write(policy.config()));
            if (!payloadChecksum.equals(policy.checksum())) {
                throw invalid("Security policy ref checksum does not match its canonical payload");
            }
        }
        Set<Long> usedPolicies = new HashSet<>();
        validateRoute(defaultRoute, "default", environment, byId, usedPolicies);
        Iterator<Map.Entry<String, JsonNode>> providers = providerRoutes.fields();
        while (providers.hasNext()) {
            Map.Entry<String, JsonNode> entry = providers.next();
            requireCode(entry.getKey(), "providerRoutes key");
            validateRoute(entry.getValue(), "provider:" + entry.getKey(), environment, byId, usedPolicies);
        }
        Iterator<Map.Entry<String, JsonNode>> apis = apiRoutes.fields();
        while (apis.hasNext()) {
            Map.Entry<String, JsonNode> entry = apis.next();
            String[] scope = entry.getKey().split(":", -1);
            if (scope.length != 2) throw invalid("apiRoutes key must be provider:api");
            requireCode(scope[0], "apiRoutes provider");
            requireCode(scope[1], "apiRoutes api");
            validateRoute(entry.getValue(), "api:" + scope[0] + ":" + scope[1], environment, byId, usedPolicies);
        }
        if (!usedPolicies.equals(byId.keySet())) {
            throw invalid("securityPolicyVersionIds contains an unreferenced policy");
        }
    }

    private void validateRoute(
            JsonNode route,
            String expectedScope,
            String environment,
            Map<Long, PolicyBundle> policies,
            Set<Long> usedPolicies) {
        if (route == null || !route.isObject()) throw invalid("Route definition must be an object");
        String mode = text(route, "mode", 16).toUpperCase(Locale.ROOT);
        String unavailable = text(route, "unavailablePolicy", 32).toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw invalid("Route mode is invalid");
        if (!UNAVAILABLE_POLICIES.contains(unavailable)) throw invalid("Route unavailablePolicy is invalid");
        if (isProd(environment) && (!"REAL".equals(mode) || "FALLBACK_REAL".equals(unavailable))) {
            throw invalid("MOCK/CANARY and FALLBACK_REAL are forbidden in PROD");
        }
        Set<String> allowedHeaders = validateStringSet(route, "allowedBusinessHeaders", HEADER);
        if (allowedHeaders.stream().anyMatch(BUILTIN_SENSITIVE_HEADERS::contains)) {
            throw invalid("allowedBusinessHeaders contains a platform-sensitive header");
        }
        Set<String> deniedHeaders = validateStringSet(route, "additionalSensitiveHeaders", HEADER);
        Set<String> allowedHosts = validateHosts(route, "allowedRealHosts");
        validateCanary(route.get("canaryRule"), "CANARY".equals(mode));
        validateFallbackResponse(route.get("fallbackResponse"), "FALLBACK_RESPONSE".equals(unavailable));

        Long headerPolicyId = optionalPositiveLong(route, "headerFilterPolicyVersionId");
        if (!"REAL".equals(mode)) {
            PolicyBundle header = requirePolicy(
                    headerPolicyId, SecurityPolicyType.SDK_HEADER_FILTER, expectedScope, policies);
            requireExactSet(route, "allowedBusinessHeaders", header.config(), "allowedBusinessHeaders");
            requireExactSet(route, "additionalSensitiveHeaders", header.config(), "additionalSensitiveHeaders");
            usedPolicies.add(header.policyVersionId());
        } else if (headerPolicyId != null) {
            throw invalid("REAL route must not carry headerFilterPolicyVersionId");
        } else if (!allowedHeaders.isEmpty() || !deniedHeaders.isEmpty()) {
            throw invalid("REAL route must not carry header filter fields");
        }

        Long fallbackPolicyId = optionalPositiveLong(route, "fallbackRealPolicyVersionId");
        if ("FALLBACK_REAL".equals(unavailable)) {
            PolicyBundle fallback = requirePolicy(
                    fallbackPolicyId, SecurityPolicyType.SDK_FALLBACK_REAL, expectedScope, policies);
            requireAllowedRealHosts(route, fallback.config());
            usedPolicies.add(fallback.policyVersionId());
        } else if (fallbackPolicyId != null) {
            throw invalid("fallbackRealPolicyVersionId requires FALLBACK_REAL");
        } else if (!allowedHosts.isEmpty()) {
            throw invalid("allowedRealHosts requires FALLBACK_REAL");
        }
    }

    private PolicyBundle requirePolicy(
            Long id,
            SecurityPolicyType type,
            String expectedScope,
            Map<Long, PolicyBundle> policies) {
        if (id == null) throw invalid(type.name() + " policyVersionId is required");
        PolicyBundle policy = policies.get(id);
        if (policy == null || !type.name().equals(policy.policyType())) {
            throw invalid("Route references a missing or wrong security policy type");
        }
        if (!expectedScope.equals(policy.scopeKey())) {
            throw invalid("Route and security policy scopeKey do not match");
        }
        return policy;
    }

    private void validateCanary(JsonNode canary, boolean required) {
        if (!required) {
            if (canary != null && !canary.isNull()) throw invalid("canaryRule requires CANARY mode");
            return;
        }
        if (canary == null || !canary.isObject()) throw invalid("CANARY route requires canaryRule");
        int constraints = validateCodeArray(canary, "apps", 128)
                + validateCodeArray(canary, "tenants", 128)
                + validateCodeArray(canary, "testAccounts", 128);
        if (constraints == 0) throw invalid("canaryRule must contain at least one constraint");
    }

    private void validateFallbackResponse(JsonNode response, boolean required) {
        if (!required) {
            if (response != null && !response.isNull()) {
                throw invalid("fallbackResponse requires FALLBACK_RESPONSE");
            }
            return;
        }
        if (response == null || !response.isObject()) {
            throw invalid("FALLBACK_RESPONSE requires fallbackResponse");
        }
        int status = response.path("status").asInt(-1);
        if (status < 100 || status > 599) throw invalid("fallbackResponse.status is invalid");
        text(response, "contentType", 128);
        JsonNode body = response.get("body");
        if (body == null || !body.isTextual()
                || body.textValue().getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw invalid("fallbackResponse.body is invalid");
        }
    }

    private void requireAllowedRealHosts(JsonNode route, JsonNode fallbackConfig) {
        Set<String> expected = stringSet(fallbackConfig, "allowedRealHosts");
        if (!expected.equals(stringSet(route, "allowedRealHosts"))) {
            throw invalid("allowedRealHosts must exactly match SDK_FALLBACK_REAL payload hosts");
        }
    }

    private void requireExactSet(JsonNode route, String routeField, JsonNode policy, String policyField) {
        if (!stringSet(route, routeField).equals(stringSet(policy, policyField))) {
            throw invalid(routeField + " must exactly match SDK_HEADER_FILTER payload");
        }
    }

    private Set<String> validateStringSet(JsonNode object, String field, Pattern pattern) {
        Set<String> result = stringSet(object, field);
        for (String value : result) {
            if (!pattern.matcher(value).matches()) throw invalid(field + " contains an invalid value");
        }
        return result;
    }

    private Set<String> stringSet(JsonNode object, String field) {
        JsonNode values = object.get(field);
        if (values == null || !values.isArray()) throw invalid(field + " must be an array");
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()
                    || !result.add(value.textValue().toLowerCase(Locale.ROOT))) {
                throw invalid(field + " contains an invalid or duplicate value");
            }
        }
        return Set.copyOf(result);
    }

    private int validateCodeArray(JsonNode object, String field, int maxLength) {
        JsonNode values = object.get(field);
        if (values == null || !values.isArray()) {
            throw invalid("canaryRule." + field + " must be an array");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()
                    || value.textValue().length() > maxLength || !unique.add(value.textValue())) {
                throw invalid("canaryRule." + field + " contains an invalid value");
            }
        }
        return unique.size();
    }

    private Long optionalPositiveLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.canConvertToLong() || !value.isIntegralNumber() || value.longValue() <= 0) {
            throw invalid(field + " must be a positive integer");
        }
        return value.longValue();
    }

    private String text(JsonNode object, String field, int maxLength) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().trim().length() > maxLength) {
            throw invalid(field + " is invalid");
        }
        return value.textValue().trim();
    }

    private void validateRuntimeBaseUri(JsonNode value, String environment) {
        if (value == null || !value.isTextual() || value.textValue().length() > 2_048) {
            throw invalid("routing.runtimeBaseUri is invalid");
        }
        try {
            URI uri = new URI(value.textValue());
            boolean allowedScheme = "https".equalsIgnoreCase(uri.getScheme())
                    || !isProd(environment) && "http".equalsIgnoreCase(uri.getScheme());
            if (!uri.isAbsolute() || !allowedScheme
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw invalid("routing.runtimeBaseUri must be an allowed HTTP(S) URL without user-info, query, or fragment");
            }
        } catch (URISyntaxException failure) {
            throw invalid("routing.runtimeBaseUri is invalid");
        }
    }

    private Set<String> validateHosts(JsonNode object, String field) {
        Set<String> hosts = stringSet(object, field);
        for (String host : hosts) {
            if (host.length() > 253 || host.indexOf('/') >= 0 || host.indexOf(':') >= 0
                    || host.indexOf('*') >= 0 || host.indexOf('@') >= 0) {
                throw invalid(field + " contains an invalid host");
            }
        }
        return hosts;
    }

    private void requireCode(String value, String field) {
        if (!CODE.matcher(value).matches()) throw invalid(field + " is invalid");
    }

    private boolean isProd(String environment) {
        return "PROD".equalsIgnoreCase(environment) || "PRODUCTION".equalsIgnoreCase(environment);
    }

    private PlatformException invalid(String message) {
        return new PlatformException(ErrorCode.INVALID_REQUEST, message);
    }

    public record PolicyBundle(long policyVersionId, String policyType, String scopeKey, String checksum, JsonNode config) {
    }
}
