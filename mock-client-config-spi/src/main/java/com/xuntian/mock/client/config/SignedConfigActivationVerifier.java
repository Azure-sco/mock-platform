package com.xuntian.mock.client.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.model.UnavailablePolicy;
import com.xuntian.mock.client.core.routing.CanaryRule;
import com.xuntian.mock.client.core.routing.FallbackResponse;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Deque;
import java.util.Set;
import java.util.regex.Pattern;

final class SignedConfigActivationVerifier {

    static final int MAX_WRAPPER_BYTES = 1024 * 1024;
    private static final int MAX_ROUTES = 5_000;
    private static final int MAX_POLICIES = 5_000;
    private static final int MAX_FALLBACK_BODY = 64 * 1024;
    private static final String SCHEMA_VERSION = "1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String HEADER_FILTER_POLICY = "SDK_HEADER_FILTER";
    private static final String FALLBACK_REAL_POLICY = "SDK_FALLBACK_REAL";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SAFE_ENVIRONMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> BUILTIN_SENSITIVE_HEADERS = sensitiveHeaders();

    private final CanonicalJson json = new CanonicalJson();
    private final ConfigSignatureKeyProvider keys;

    SignedConfigActivationVerifier(ConfigSignatureKeyProvider keys) {
        if (keys == null) {
            throw new NullPointerException("keys");
        }
        this.keys = keys;
    }

    VerifiedConfigActivation verify(
            byte[] raw,
            String expectedAppCode,
            String expectedEnvironment,
            long activeConfigVersion,
            Instant now) throws ConfigActivationException {
        if (raw == null || raw.length == 0 || raw.length > MAX_WRAPPER_BYTES) {
            throw failure("WRAPPER_SIZE_INVALID", ActivationMetadata.EMPTY);
        }

        JsonNode root;
        ActivationMetadata metadata = ActivationMetadata.EMPTY;
        try {
            root = json.parse(raw);
            metadata = metadata(root);
        } catch (IOException | RuntimeException malformed) {
            throw failure("WRAPPER_MALFORMED", metadata);
        }
        if (root == null || !root.isObject()) {
            throw failure("WRAPPER_MALFORMED", metadata);
        }
        validateJsonComplexity(root, metadata);
        byte[] canonicalWrapper = canonicalBytes(root, metadata);
        if (!Arrays.equals(raw, canonicalWrapper)) {
            throw failure("WRAPPER_NOT_CANONICAL", metadata);
        }

        SignedWrapperDto wrapper;
        try {
            wrapper = json.convert(root, SignedWrapperDto.class);
        } catch (IOException | RuntimeException malformed) {
            throw failure("WRAPPER_MALFORMED", metadata);
        }
        validateWrapper(wrapper, metadata, expectedAppCode, expectedEnvironment, now);

        JsonNode activationNode = root.get("activation");
        byte[] activationBytes = canonicalBytes(activationNode, metadata);
        verifyChecksum(activationBytes, wrapper.wrapperChecksum, "WRAPPER_CHECKSUM_MISMATCH", metadata);
        verifySignature(
                activationBytes,
                wrapper.wrapperSignature,
                wrapper.wrapperSignatureKeyId,
                wrapper.wrapperSignatureAlgorithm,
                "WRAPPER_SIGNATURE_INVALID",
                metadata);

        ActivationDto activation = wrapper.activation;
        SignedEnvelopeDto envelope = activation.envelope;
        if (!activation.envelopeChecksum.equals(envelope.checksum)) {
            throw failure("ENVELOPE_REFERENCE_MISMATCH", metadata);
        }
        SnapshotDto snapshot = envelope.snapshot;
        if (snapshot == null
                || !activation.appCode.equals(snapshot.appCode)
                || !activation.environment.equals(snapshot.environment)
                || activation.configVersion != snapshot.configVersion) {
            throw failure("ENVELOPE_REFERENCE_MISMATCH", metadata);
        }

        JsonNode snapshotNode = activationNode.path("envelope").get("snapshot");
        byte[] snapshotBytes = canonicalBytes(snapshotNode, metadata);
        verifyChecksum(snapshotBytes, envelope.checksum, "ENVELOPE_CHECKSUM_MISMATCH", metadata);
        verifySignature(
                snapshotBytes,
                envelope.signature,
                envelope.signatureKeyId,
                envelope.signatureAlgorithm,
                "ENVELOPE_SIGNATURE_INVALID",
                metadata);

        validateSnapshot(snapshot, metadata, expectedAppCode, expectedEnvironment, activeConfigVersion, now);
        CompiledRouting compiled = compile(snapshot, metadata);
        return new VerifiedConfigActivation(metadata, compiled.snapshot, compiled.policyReferences);
    }

    private void validateWrapper(
            SignedWrapperDto wrapper,
            ActivationMetadata metadata,
            String expectedAppCode,
            String expectedEnvironment,
            Instant now) throws ConfigActivationException {
        if (wrapper == null || wrapper.activation == null) {
            throw failure("WRAPPER_MALFORMED", metadata);
        }
        ActivationDto activation = wrapper.activation;
        requireEquals(activation.schemaVersion, SCHEMA_VERSION, "SCHEMA_UNSUPPORTED", metadata);
        requireSafeId(activation.activationId, "ACTIVATION_ID_INVALID", metadata);
        requireSafeName(activation.appCode, "SCOPE_INVALID", metadata);
        requireEnvironment(activation.environment, metadata);
        requirePositiveDecimalId(activation.envelopeId, "ENVELOPE_ID_INVALID", metadata);
        if (activation.configVersion <= 0L || activation.envelope == null) {
            throw failure("ENVELOPE_REFERENCE_MISMATCH", metadata);
        }
        requireChecksum(activation.envelopeChecksum, "ENVELOPE_REFERENCE_MISMATCH", metadata);
        Instant publishedAt = parseInstant(activation.publishedAt, "PUBLISHED_AT_INVALID", metadata);
        if (publishedAt.isAfter(now.plusSeconds(300L))) {
            throw failure("PUBLISHED_AT_IN_FUTURE", metadata);
        }
        if (!expectedAppCode.equals(activation.appCode)
                || !expectedEnvironment.equals(activation.environment)) {
            throw failure("SCOPE_MISMATCH", metadata);
        }
        requireChecksum(wrapper.wrapperChecksum, "WRAPPER_CHECKSUM_INVALID", metadata);
        requireSignatureFields(
                wrapper.wrapperSignature,
                wrapper.wrapperSignatureKeyId,
                wrapper.wrapperSignatureAlgorithm,
                metadata);
        if (activation.envelope.snapshot == null) {
            throw failure("ENVELOPE_MALFORMED", metadata);
        }
        requireChecksum(activation.envelope.checksum, "ENVELOPE_CHECKSUM_INVALID", metadata);
        requireSignatureFields(
                activation.envelope.signature,
                activation.envelope.signatureKeyId,
                activation.envelope.signatureAlgorithm,
                metadata);
    }

    private void validateSnapshot(
            SnapshotDto snapshot,
            ActivationMetadata metadata,
            String expectedAppCode,
            String expectedEnvironment,
            long activeConfigVersion,
            Instant now) throws ConfigActivationException {
        requireEquals(snapshot.schemaVersion, SCHEMA_VERSION, "SCHEMA_UNSUPPORTED", metadata);
        requireSafeName(snapshot.appCode, "SCOPE_INVALID", metadata);
        requireEnvironment(snapshot.environment, metadata);
        if (!expectedAppCode.equals(snapshot.appCode)
                || !expectedEnvironment.equals(snapshot.environment)) {
            throw failure("SCOPE_MISMATCH", metadata);
        }
        if (snapshot.configVersion <= activeConfigVersion) {
            throw failure("CONFIG_VERSION_ROLLBACK", metadata);
        }
        Instant effectiveAt = parseInstant(snapshot.effectiveAt, "EFFECTIVE_AT_INVALID", metadata);
        if (effectiveAt.isAfter(now)) {
            throw failure("CONFIG_NOT_EFFECTIVE", metadata);
        }
        if (snapshot.expireAt != null) {
            Instant expireAt = parseInstant(snapshot.expireAt, "EXPIRE_AT_INVALID", metadata);
            if (!expireAt.isAfter(effectiveAt)) {
                throw failure("VALIDITY_WINDOW_INVALID", metadata);
            }
            if (!expireAt.isAfter(now)) {
                throw failure("CONFIG_EXPIRED", metadata);
            }
        }
        if (snapshot.routing == null
                || snapshot.securityPolicyRefs == null
                || snapshot.securityPolicyPayloads == null) {
            throw failure("SNAPSHOT_INCOMPLETE", metadata);
        }
    }

    private CompiledRouting compile(SnapshotDto snapshot, ActivationMetadata metadata)
            throws ConfigActivationException {
        Map<Long, PolicyMaterial> policies = compilePolicies(
                snapshot.securityPolicyRefs,
                snapshot.securityPolicyPayloads,
                metadata);
        RoutingDto routing = snapshot.routing;
        if (routing.defaultRoute == null || routing.providerRoutes == null || routing.apiRoutes == null) {
            throw failure("ROUTING_INCOMPLETE", metadata);
        }
        if (routing.allowRequestOverride == null) {
            throw failure("ROUTING_INCOMPLETE", metadata);
        }
        if (routing.providerRoutes.size() + routing.apiRoutes.size() > MAX_ROUTES) {
            throw failure("ROUTING_LIMIT_EXCEEDED", metadata);
        }

        URI runtimeBaseUri = parseRuntimeBaseUri(routing.runtimeBaseUri, snapshot.environment, metadata);
        RoutingSnapshot.Builder builder = RoutingSnapshot.builder()
                .configVersion(snapshot.configVersion)
                .observedConfigVersion(snapshot.configVersion)
                .mockConfigValid(true)
                .mockConfigExpiresAt(snapshot.expireAt == null
                        ? null
                        : parseInstant(snapshot.expireAt, "EXPIRE_AT_INVALID", metadata))
                .appCode(snapshot.appCode)
                .environment(snapshot.environment)
                .runtimeBaseUri(runtimeBaseUri)
                .allowRequestOverride(routing.allowRequestOverride.booleanValue());
        Set<Long> usedPolicies = new HashSet<Long>();
        RouteConfig defaultRoute = compileRoute(
                routing.defaultRoute,
                "default",
                snapshot.environment,
                policies,
                usedPolicies,
                metadata);
        builder.defaultRoute(defaultRoute);

        for (Map.Entry<String, RouteDto> entry : routing.providerRoutes.entrySet()) {
            String provider = entry.getKey();
            requireSafeName(provider, "PROVIDER_INVALID", metadata);
            builder.providerRoute(provider, compileRoute(
                    entry.getValue(),
                    "provider:" + provider,
                    snapshot.environment,
                    policies,
                    usedPolicies,
                    metadata));
        }
        for (Map.Entry<String, RouteDto> entry : routing.apiRoutes.entrySet()) {
            String[] parts = splitApiKey(entry.getKey(), metadata);
            builder.apiRoute(parts[0], parts[1], compileRoute(
                    entry.getValue(),
                    "api:" + parts[0] + ':' + parts[1],
                    snapshot.environment,
                    policies,
                    usedPolicies,
                    metadata));
        }
        if (!usedPolicies.equals(policies.keySet())) {
            throw failure("UNUSED_SECURITY_POLICY", metadata);
        }

        List<SdkSecurityPolicyRef> refs = new ArrayList<SdkSecurityPolicyRef>();
        for (PolicyRefDto ref : snapshot.securityPolicyRefs) {
            refs.add(new SdkSecurityPolicyRef(
                    ref.policyVersionId,
                    ref.policyType,
                    ref.scopeKey,
                    ref.checksum));
        }
        return new CompiledRouting(builder.build(), refs);
    }

    private Map<Long, PolicyMaterial> compilePolicies(
            List<PolicyRefDto> refs,
            Map<String, JsonNode> payloads,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (refs.size() > MAX_POLICIES || payloads.size() > MAX_POLICIES || refs.size() != payloads.size()) {
            throw failure("SECURITY_POLICY_SET_INVALID", metadata);
        }
        Map<Long, PolicyMaterial> policies = new HashMap<Long, PolicyMaterial>();
        Set<String> expectedPayloadKeys = new HashSet<String>();
        for (PolicyRefDto ref : refs) {
            if (ref == null || ref.policyVersionId <= 0L) {
                throw failure("SECURITY_POLICY_REF_INVALID", metadata);
            }
            requirePolicyType(ref.policyType, metadata);
            requireScopeKey(ref.scopeKey, metadata);
            requireChecksum(ref.checksum, "SECURITY_POLICY_CHECKSUM_INVALID", metadata);
            if (policies.containsKey(ref.policyVersionId)) {
                throw failure("SECURITY_POLICY_REF_DUPLICATE", metadata);
            }
            String payloadKey = Long.toString(ref.policyVersionId);
            JsonNode payload = payloads.get(payloadKey);
            if (payload == null || !payload.isObject()) {
                throw failure("SECURITY_POLICY_PAYLOAD_MISSING", metadata);
            }
            byte[] bytes = canonicalBytes(payload, metadata);
            verifyChecksum(bytes, ref.checksum, "SECURITY_POLICY_CHECKSUM_MISMATCH", metadata);
            PolicyMaterial material = compilePolicyMaterial(ref, payload, metadata);
            policies.put(ref.policyVersionId, material);
            expectedPayloadKeys.add(payloadKey);
        }
        if (!expectedPayloadKeys.equals(payloads.keySet())) {
            throw failure("SECURITY_POLICY_PAYLOAD_UNREFERENCED", metadata);
        }
        return policies;
    }

    private PolicyMaterial compilePolicyMaterial(
            PolicyRefDto ref,
            JsonNode payload,
            ActivationMetadata metadata) throws ConfigActivationException {
        try {
            if (HEADER_FILTER_POLICY.equals(ref.policyType)) {
                HeaderFilterPayloadDto value = json.convert(payload, HeaderFilterPayloadDto.class);
                Set<String> allowed = validatedHeaders(value.allowedBusinessHeaders, true, metadata);
                Set<String> denied = validatedHeaders(value.additionalSensitiveHeaders, false, metadata);
                return PolicyMaterial.header(ref, allowed, denied);
            }
            FallbackRealPayloadDto value = json.convert(payload, FallbackRealPayloadDto.class);
            Set<String> hosts = validatedHosts(value.allowedRealHosts, metadata);
            return PolicyMaterial.fallback(ref, hosts);
        } catch (IOException | RuntimeException malformed) {
            throw failure("SECURITY_POLICY_PAYLOAD_INVALID", metadata);
        }
    }

    private RouteConfig compileRoute(
            RouteDto route,
            String scopeKey,
            String environment,
            Map<Long, PolicyMaterial> policies,
            Set<Long> usedPolicies,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (route == null
                || route.mode == null
                || route.unavailablePolicy == null
                || route.allowedBusinessHeaders == null
                || route.additionalSensitiveHeaders == null
                || route.allowedRealHosts == null) {
            throw failure("ROUTE_INCOMPLETE", metadata);
        }
        MockMode mode;
        UnavailablePolicy unavailablePolicy;
        try {
            mode = MockMode.valueOf(route.mode);
            unavailablePolicy = UnavailablePolicy.valueOf(route.unavailablePolicy);
        } catch (IllegalArgumentException invalidEnum) {
            throw failure("ROUTE_ENUM_INVALID", metadata);
        }
        if (isProduction(environment)
                && (mode != MockMode.REAL || unavailablePolicy == UnavailablePolicy.FALLBACK_REAL)) {
            throw failure("PRODUCTION_MOCK_FORBIDDEN", metadata);
        }

        Set<String> inlineAllowed = validatedHeaders(route.allowedBusinessHeaders, true, metadata);
        Set<String> inlineDenied = validatedHeaders(route.additionalSensitiveHeaders, false, metadata);
        Set<String> inlineHosts = validatedHosts(route.allowedRealHosts, metadata);
        RouteConfig.Builder builder = RouteConfig.builder(mode).unavailablePolicy(unavailablePolicy);

        if (mode == MockMode.CANARY) {
            builder.canaryRule(compileCanary(route.canaryRule, metadata));
        } else if (route.canaryRule != null) {
            throw failure("CANARY_RULE_UNEXPECTED", metadata);
        }

        if (mode == MockMode.MOCK || mode == MockMode.CANARY) {
            PolicyMaterial headerPolicy = requirePolicy(
                    route.headerFilterPolicyVersionId,
                    HEADER_FILTER_POLICY,
                    scopeKey,
                    policies,
                    metadata);
            if (!inlineAllowed.equals(headerPolicy.allowedHeaders)
                    || !inlineDenied.equals(headerPolicy.deniedHeaders)) {
                throw failure("HEADER_FILTER_POLICY_MISMATCH", metadata);
            }
            usedPolicies.add(headerPolicy.ref.policyVersionId);
            for (String header : inlineAllowed) {
                builder.allowBusinessHeader(header);
            }
            for (String header : inlineDenied) {
                builder.denyHeader(header);
            }
        } else if (route.headerFilterPolicyVersionId != null
                || !inlineAllowed.isEmpty()
                || !inlineDenied.isEmpty()) {
            throw failure("HEADER_FILTER_POLICY_UNEXPECTED", metadata);
        }

        if (unavailablePolicy == UnavailablePolicy.FALLBACK_REAL) {
            PolicyMaterial fallbackPolicy = requirePolicy(
                    route.fallbackRealPolicyVersionId,
                    FALLBACK_REAL_POLICY,
                    scopeKey,
                    policies,
                    metadata);
            if (!inlineHosts.equals(fallbackPolicy.allowedHosts)) {
                throw failure("FALLBACK_REAL_POLICY_MISMATCH", metadata);
            }
            usedPolicies.add(fallbackPolicy.ref.policyVersionId);
            for (String host : inlineHosts) {
                builder.allowRealHost(host);
            }
        } else if (route.fallbackRealPolicyVersionId != null || !inlineHosts.isEmpty()) {
            throw failure("FALLBACK_REAL_POLICY_UNEXPECTED", metadata);
        }

        if (unavailablePolicy == UnavailablePolicy.FALLBACK_RESPONSE) {
            builder.fallbackResponse(compileFallbackResponse(route.fallbackResponse, metadata));
        } else if (route.fallbackResponse != null) {
            throw failure("FALLBACK_RESPONSE_UNEXPECTED", metadata);
        }
        return builder.build();
    }

    private CanaryRule compileCanary(CanaryDto canary, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (canary == null || canary.apps == null || canary.tenants == null || canary.testAccounts == null) {
            throw failure("CANARY_RULE_INCOMPLETE", metadata);
        }
        if (canary.apps.isEmpty() && canary.tenants.isEmpty() && canary.testAccounts.isEmpty()) {
            throw failure("CANARY_RULE_EMPTY", metadata);
        }
        CanaryRule.Builder builder = CanaryRule.builder();
        for (String app : validatedNames(canary.apps, "CANARY_APP_INVALID", metadata)) {
            builder.app(app);
        }
        for (String tenant : validatedOpaqueNames(
                canary.tenants,
                "CANARY_TENANT_INVALID",
                metadata)) {
            builder.tenant(tenant);
        }
        for (String account : validatedOpaqueNames(
                canary.testAccounts,
                "CANARY_ACCOUNT_INVALID",
                metadata)) {
            builder.testAccount(account);
        }
        return builder.build();
    }

    private FallbackResponse compileFallbackResponse(
            FallbackResponseDto fallback,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (fallback == null
                || fallback.status < 100
                || fallback.status > 599
                || fallback.contentType == null
                || fallback.contentType.isEmpty()
                || fallback.contentType.length() > 128
                || containsControl(fallback.contentType)
                || fallback.body == null
                || fallback.body.getBytes(StandardCharsets.UTF_8).length > MAX_FALLBACK_BODY) {
            throw failure("FALLBACK_RESPONSE_INVALID", metadata);
        }
        return new FallbackResponse(fallback.status, fallback.contentType, fallback.body);
    }

    private PolicyMaterial requirePolicy(
            Long policyVersionId,
            String type,
            String scopeKey,
            Map<Long, PolicyMaterial> policies,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (policyVersionId == null) {
            throw failure("REQUIRED_SECURITY_POLICY_MISSING", metadata);
        }
        PolicyMaterial material = policies.get(policyVersionId);
        if (material == null
                || !type.equals(material.ref.policyType)
                || !scopeKey.equals(material.ref.scopeKey)) {
            throw failure("SECURITY_POLICY_SCOPE_MISMATCH", metadata);
        }
        return material;
    }

    private Set<String> validatedHeaders(
            List<String> headers,
            boolean allowed,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (headers == null || headers.size() > 256) {
            throw failure("HEADER_POLICY_INVALID", metadata);
        }
        Set<String> result = new HashSet<String>();
        for (String header : headers) {
            if (!isHeaderName(header)) {
                throw failure("HEADER_POLICY_INVALID", metadata);
            }
            String normalized = header.toLowerCase(Locale.ROOT);
            if (allowed && BUILTIN_SENSITIVE_HEADERS.contains(normalized)) {
                throw failure("SENSITIVE_HEADER_ALLOW_FORBIDDEN", metadata);
            }
            if (!result.add(normalized)) {
                throw failure("HEADER_POLICY_DUPLICATE", metadata);
            }
        }
        return result;
    }

    private Set<String> validatedHosts(List<String> hosts, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (hosts == null || hosts.size() > 256) {
            throw failure("FALLBACK_HOST_POLICY_INVALID", metadata);
        }
        Set<String> result = new HashSet<String>();
        for (String host : hosts) {
            if (host == null
                    || host.isEmpty()
                    || host.length() > 253
                    || containsControl(host)
                    || host.indexOf('/') >= 0
                    || host.indexOf(':') >= 0
                    || host.indexOf('*') >= 0
                    || host.indexOf('@') >= 0) {
                throw failure("FALLBACK_HOST_POLICY_INVALID", metadata);
            }
            if (!result.add(host.toLowerCase(Locale.ROOT))) {
                throw failure("FALLBACK_HOST_POLICY_DUPLICATE", metadata);
            }
        }
        return result;
    }

    private Set<String> validatedNames(
            List<String> names,
            String errorCode,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (names.size() > 1_000) {
            throw failure(errorCode, metadata);
        }
        Set<String> result = new HashSet<String>();
        for (String name : names) {
            if (!isSafeName(name) || !result.add(name)) {
                throw failure(errorCode, metadata);
            }
        }
        return result;
    }

    private Set<String> validatedOpaqueNames(
            List<String> names,
            String errorCode,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (names.size() > 1_000) {
            throw failure(errorCode, metadata);
        }
        Set<String> result = new HashSet<String>();
        for (String name : names) {
            if (name == null
                    || name.isEmpty()
                    || name.length() > 128
                    || containsControl(name)
                    || !result.add(name)) {
                throw failure(errorCode, metadata);
            }
        }
        return result;
    }

    private URI parseRuntimeBaseUri(
            String value,
            String environment,
            ActivationMetadata metadata)
            throws ConfigActivationException {
        if (value == null || value.length() > 2_048 || containsControl(value)) {
            throw failure("RUNTIME_URI_INVALID", metadata);
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute()
                    || (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw failure("RUNTIME_URI_INVALID", metadata);
            }
            if (isProduction(environment) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw failure("RUNTIME_URI_INSECURE_PRODUCTION", metadata);
            }
            return uri;
        } catch (URISyntaxException invalid) {
            throw failure("RUNTIME_URI_INVALID", metadata);
        }
    }

    private String[] splitApiKey(String key, ActivationMetadata metadata) throws ConfigActivationException {
        if (key == null) {
            throw failure("API_ROUTE_KEY_INVALID", metadata);
        }
        int separator = key.indexOf(':');
        if (separator <= 0 || separator != key.lastIndexOf(':') || separator == key.length() - 1) {
            throw failure("API_ROUTE_KEY_INVALID", metadata);
        }
        String provider = key.substring(0, separator);
        String api = key.substring(separator + 1);
        requireSafeName(provider, "PROVIDER_INVALID", metadata);
        requireSafeName(api, "API_INVALID", metadata);
        return new String[]{provider, api};
    }

    private void verifySignature(
            byte[] content,
            String encodedSignature,
            String keyId,
            String algorithm,
            String errorCode,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (!SIGNATURE_ALGORITHM.equals(algorithm)) {
            throw failure("SIGNATURE_ALGORITHM_UNSUPPORTED", metadata);
        }
        PublicKey key;
        try {
            key = keys.findTrustedKey(keyId);
        } catch (RuntimeException unavailable) {
            throw failure("SIGNATURE_KEY_UNAVAILABLE", metadata);
        }
        if (!(key instanceof RSAKey) || ((RSAKey) key).getModulus().bitLength() < 2048) {
            throw failure("SIGNATURE_KEY_UNKNOWN_OR_WEAK", metadata);
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException malformed) {
            throw failure(errorCode, metadata);
        }
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(key);
            verifier.update(content);
            if (!verifier.verify(signatureBytes)) {
                throw failure(errorCode, metadata);
            }
        } catch (GeneralSecurityException invalid) {
            throw failure(errorCode, metadata);
        }
    }

    private void verifyChecksum(
            byte[] content,
            String expected,
            String errorCode,
            ActivationMetadata metadata) throws ConfigActivationException {
        byte[] actual;
        try {
            actual = sha256Hex(content).getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException impossible) {
            throw failure("CRYPTO_UNAVAILABLE", metadata);
        }
        if (!MessageDigest.isEqual(actual, expected.getBytes(StandardCharsets.US_ASCII))) {
            throw failure(errorCode, metadata);
        }
    }

    private String sha256Hex(byte[] content) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private byte[] canonicalBytes(JsonNode node, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (node == null || !node.isObject()) {
            throw failure("SIGNED_CONTENT_MALFORMED", metadata);
        }
        try {
            return json.bytes(node);
        } catch (IOException | RuntimeException invalid) {
            throw failure("SIGNED_CONTENT_MALFORMED", metadata);
        }
    }

    private ActivationMetadata metadata(JsonNode root) {
        if (root == null || !root.isObject()) {
            return ActivationMetadata.EMPTY;
        }
        JsonNode activation = root.get("activation");
        if (activation == null || !activation.isObject()) {
            return ActivationMetadata.EMPTY;
        }
        return new ActivationMetadata(
                safeIdValue(activation.get("activationId")),
                safeNameValue(activation.get("appCode")),
                safeEnvironmentValue(activation.get("environment")),
                safeIdValue(activation.get("envelopeId")),
                positiveLongValue(activation.get("configVersion")));
    }

    private void validateJsonComplexity(JsonNode root, ActivationMetadata metadata)
            throws ConfigActivationException {
        Deque<NodeDepth> pending = new ArrayDeque<NodeDepth>();
        pending.push(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.pop();
            nodes++;
            if (nodes > 100_000 || current.depth > 64) {
                throw failure("WRAPPER_COMPLEXITY_LIMIT_EXCEEDED", metadata);
            }
            if (current.node.isContainerNode()) {
                for (JsonNode child : current.node) {
                    pending.push(new NodeDepth(child, current.depth + 1));
                }
            }
        }
    }

    private String safeIdValue(JsonNode node) {
        return node != null && node.isTextual() && isSafeId(node.textValue()) ? node.textValue() : null;
    }

    private String safeNameValue(JsonNode node) {
        return node != null && node.isTextual() && isSafeName(node.textValue()) ? node.textValue() : null;
    }

    private String safeEnvironmentValue(JsonNode node) {
        return node != null && node.isTextual() && SAFE_ENVIRONMENT.matcher(node.textValue()).matches()
                ? node.textValue()
                : null;
    }

    private long positiveLongValue(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0L
                ? node.longValue()
                : -1L;
    }

    private Instant parseInstant(String value, String errorCode, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (value == null || value.length() > 64 || containsControl(value)) {
            throw failure(errorCode, metadata);
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException invalid) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException alsoInvalid) {
                throw failure(errorCode, metadata);
            }
        }
    }

    private void requireSignatureFields(
            String signature,
            String keyId,
            String algorithm,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (signature == null || signature.isEmpty() || signature.length() > 2_048) {
            throw failure("SIGNATURE_MALFORMED", metadata);
        }
        requireSafeId(keyId, "SIGNATURE_KEY_ID_INVALID", metadata);
        requireEquals(algorithm, SIGNATURE_ALGORITHM, "SIGNATURE_ALGORITHM_UNSUPPORTED", metadata);
    }

    private void requirePolicyType(String type, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (!(HEADER_FILTER_POLICY.equals(type) || FALLBACK_REAL_POLICY.equals(type))) {
            throw failure("SECURITY_POLICY_TYPE_UNSUPPORTED", metadata);
        }
    }

    private void requireScopeKey(String scopeKey, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (scopeKey == null || scopeKey.length() > 270 || containsControl(scopeKey)) {
            throw failure("SECURITY_POLICY_SCOPE_INVALID", metadata);
        }
        if ("default".equals(scopeKey)) {
            return;
        }
        if (scopeKey.startsWith("provider:")) {
            requireSafeName(scopeKey.substring("provider:".length()), "SECURITY_POLICY_SCOPE_INVALID", metadata);
            return;
        }
        if (scopeKey.startsWith("api:")) {
            String remainder = scopeKey.substring("api:".length());
            int separator = remainder.indexOf(':');
            if (separator > 0 && separator == remainder.lastIndexOf(':')) {
                requireSafeName(remainder.substring(0, separator), "SECURITY_POLICY_SCOPE_INVALID", metadata);
                requireSafeName(remainder.substring(separator + 1), "SECURITY_POLICY_SCOPE_INVALID", metadata);
                return;
            }
        }
        throw failure("SECURITY_POLICY_SCOPE_INVALID", metadata);
    }

    private void requireChecksum(String checksum, String errorCode, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (checksum == null || !CHECKSUM.matcher(checksum).matches()) {
            throw failure(errorCode, metadata);
        }
    }

    private void requireEquals(
            String actual,
            String expected,
            String errorCode,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (!expected.equals(actual)) {
            throw failure(errorCode, metadata);
        }
    }

    private void requireSafeId(String value, String errorCode, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (!isSafeId(value)) {
            throw failure(errorCode, metadata);
        }
    }

    private void requirePositiveDecimalId(
            String value,
            String errorCode,
            ActivationMetadata metadata) throws ConfigActivationException {
        if (value == null || value.isEmpty() || value.length() > 19) {
            throw failure(errorCode, metadata);
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                throw failure(errorCode, metadata);
            }
        }
        try {
            if (Long.parseLong(value) <= 0L) {
                throw failure(errorCode, metadata);
            }
        } catch (NumberFormatException invalid) {
            throw failure(errorCode, metadata);
        }
    }

    private void requireSafeName(String value, String errorCode, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (!isSafeName(value)) {
            throw failure(errorCode, metadata);
        }
    }

    private void requireEnvironment(String value, ActivationMetadata metadata)
            throws ConfigActivationException {
        if (value == null || !SAFE_ENVIRONMENT.matcher(value).matches()) {
            throw failure("SCOPE_INVALID", metadata);
        }
    }

    private boolean isSafeName(String value) {
        return value != null && SAFE_NAME.matcher(value).matches();
    }

    private boolean isSafeId(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }

    private boolean isProduction(String environment) {
        return "PROD".equalsIgnoreCase(environment) || "PRODUCTION".equalsIgnoreCase(environment);
    }

    private boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isHeaderName(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            boolean alphaNumeric = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9');
            if (!alphaNumeric && "!#$%&'*+-.^_`|~".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> sensitiveHeaders() {
        Set<String> headers = new HashSet<String>();
        Collections.addAll(
                headers,
                "authorization",
                "proxy-authorization",
                "cookie",
                "set-cookie",
                "x-api-key",
                "x-app-secret",
                "signature",
                "x-signature",
                "x-third-party-signature");
        return Collections.unmodifiableSet(headers);
    }

    private ConfigActivationException failure(String errorCode, ActivationMetadata metadata) {
        return new ConfigActivationException(errorCode, metadata == null ? ActivationMetadata.EMPTY : metadata);
    }

    private static final class CompiledRouting {
        private final RoutingSnapshot snapshot;
        private final List<SdkSecurityPolicyRef> policyReferences;

        private CompiledRouting(
                RoutingSnapshot snapshot,
                List<SdkSecurityPolicyRef> policyReferences) {
            this.snapshot = snapshot;
            this.policyReferences = policyReferences;
        }
    }

    private static final class NodeDepth {
        private final JsonNode node;
        private final int depth;

        private NodeDepth(JsonNode node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    private static final class PolicyMaterial {
        private final PolicyRefDto ref;
        private final Set<String> allowedHeaders;
        private final Set<String> deniedHeaders;
        private final Set<String> allowedHosts;

        private PolicyMaterial(
                PolicyRefDto ref,
                Set<String> allowedHeaders,
                Set<String> deniedHeaders,
                Set<String> allowedHosts) {
            this.ref = ref;
            this.allowedHeaders = allowedHeaders;
            this.deniedHeaders = deniedHeaders;
            this.allowedHosts = allowedHosts;
        }

        private static PolicyMaterial header(PolicyRefDto ref, Set<String> allowed, Set<String> denied) {
            return new PolicyMaterial(ref, allowed, denied, Collections.<String>emptySet());
        }

        private static PolicyMaterial fallback(PolicyRefDto ref, Set<String> hosts) {
            return new PolicyMaterial(
                    ref,
                    Collections.<String>emptySet(),
                    Collections.<String>emptySet(),
                    hosts);
        }
    }

    public static final class SignedWrapperDto {
        public ActivationDto activation;
        public String wrapperChecksum;
        public String wrapperSignature;
        public String wrapperSignatureKeyId;
        public String wrapperSignatureAlgorithm;
    }

    public static final class ActivationDto {
        public String schemaVersion;
        public String activationId;
        public String appCode;
        public String environment;
        public String envelopeId;
        public long configVersion;
        public String envelopeChecksum;
        public String publishedAt;
        public SignedEnvelopeDto envelope;
    }

    public static final class SignedEnvelopeDto {
        public SnapshotDto snapshot;
        public String checksum;
        public String signature;
        public String signatureKeyId;
        public String signatureAlgorithm;
    }

    public static final class SnapshotDto {
        public String schemaVersion;
        public String appCode;
        public String environment;
        public long configVersion;
        public RoutingDto routing;
        public List<PolicyRefDto> securityPolicyRefs;
        public Map<String, JsonNode> securityPolicyPayloads;
        public String effectiveAt;
        public String expireAt;
    }

    public static final class RoutingDto {
        public String runtimeBaseUri;
        public Boolean allowRequestOverride;
        public RouteDto defaultRoute;
        public Map<String, RouteDto> providerRoutes;
        public Map<String, RouteDto> apiRoutes;
    }

    public static final class RouteDto {
        public String mode;
        public String unavailablePolicy;
        public FallbackResponseDto fallbackResponse;
        public CanaryDto canaryRule;
        public List<String> allowedBusinessHeaders;
        public List<String> additionalSensitiveHeaders;
        public List<String> allowedRealHosts;
        public Long headerFilterPolicyVersionId;
        public Long fallbackRealPolicyVersionId;
    }

    public static final class CanaryDto {
        public List<String> apps;
        public List<String> tenants;
        public List<String> testAccounts;
    }

    public static final class FallbackResponseDto {
        public int status;
        public String contentType;
        public String body;
    }

    public static final class PolicyRefDto {
        public long policyVersionId;
        public String policyType;
        public String scopeKey;
        public String checksum;
    }

    public static final class HeaderFilterPayloadDto {
        public List<String> allowedBusinessHeaders;
        public List<String> additionalSensitiveHeaders;
    }

    public static final class FallbackRealPayloadDto {
        public List<String> allowedRealHosts;
    }
}
