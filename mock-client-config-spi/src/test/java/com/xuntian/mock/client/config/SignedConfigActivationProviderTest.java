package com.xuntian.mock.client.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.failure.MockConfigInvalidException;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RouteResolver;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import com.xuntian.mock.client.core.security.HeaderSanitizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedConfigActivationProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private KeyPair trustedKey;
    private KeyPair unknownKey;
    private ScheduledThreadPoolExecutor ackExecutor;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trustedKey = generator.generateKeyPair();
        unknownKey = generator.generateKeyPair();
    }

    @AfterEach
    void tearDown() {
        if (ackExecutor != null) {
            ackExecutor.shutdownNow();
        }
    }

    @Test
    void verifiesBothSignaturesAndAtomicallyAppliesRoutingAndHeaderPolicy() throws Exception {
        List<SdkConfigAck> acknowledgements = new ArrayList<SdkConfigAck>();
        SignedConfigActivationProvider provider = provider(acknowledgements);
        List<RoutingSnapshot> observed = new ArrayList<RoutingSnapshot>();
        provider.registerListener((previous, current) -> observed.add(current));

        ConfigApplyResult result = provider.onConfigChanged(wrapper(2, "X-Business-V2", trustedKey, "trusted"));

        assertThat(result.status()).isEqualTo(ConfigApplyResult.Status.APPLIED);
        assertThat(provider.isReady()).isTrue();
        assertThat(provider.current().configVersion()).isEqualTo(2);
        assertThat(provider.current().mockConfigValid()).isTrue();
        assertThat(provider.current().route("PAY", "CREATE").mode()).isEqualTo(MockMode.MOCK);
        assertThat(provider.current().route("PAY", "CREATE").allowedBusinessHeaders())
                .containsExactly("x-business-v2");
        assertThat(observed).containsExactly(provider.current());
        assertThat(acknowledgements).hasSize(1);
        assertThat(acknowledgements.get(0).status()).isEqualTo(SdkConfigAck.Status.APPLIED);
        assertThat(acknowledgements.get(0).idempotencyKey())
                .isEqualTo("activation-2\u0000sdk-instance-1");
    }

    @Test
    void duplicateDeliveryReplaysTheSameAppliedAcknowledgementIdempotently() throws Exception {
        List<SdkConfigAck> acknowledgements = new ArrayList<SdkConfigAck>();
        SignedConfigActivationProvider provider = provider(acknowledgements);
        byte[] activation = wrapper(2, "X-Business", trustedKey, "trusted");

        ConfigApplyResult first = provider.onConfigChanged(activation);
        RoutingSnapshot applied = provider.current();
        ConfigApplyResult replay = provider.onConfigChanged(activation);

        assertThat(first.status()).isEqualTo(ConfigApplyResult.Status.APPLIED);
        assertThat(replay.status()).isEqualTo(ConfigApplyResult.Status.APPLIED);
        assertThat(provider.current()).isSameAs(applied);
        assertThat(acknowledgements).hasSize(2);
        assertThat(acknowledgements.get(1)).isSameAs(acknowledgements.get(0));
        assertThat(acknowledgements.get(1).idempotencyKey())
                .isEqualTo(acknowledgements.get(0).idempotencyKey());
    }

    @Test
    void tamperedHigherVersionPreservesRealAndFailsCurrentMockRoutesUntilRecovery() throws Exception {
        List<SdkConfigAck> acknowledgements = new ArrayList<SdkConfigAck>();
        SignedConfigActivationProvider provider = provider(acknowledgements);
        assertThat(provider.onConfigChanged(wrapper(2, "X-Business-V2", trustedKey, "trusted")).status())
                .isEqualTo(ConfigApplyResult.Status.APPLIED);

        ObjectNode tampered = (ObjectNode) mapper.readTree(wrapper(3, "X-Business-V3", trustedKey, "trusted"));
        ((ObjectNode) tampered.at("/activation/envelope/snapshot/routing/providerRoutes/PAY"))
                .put("mode", "REAL");
        resignWrapperOnly(tampered, trustedKey.getPrivate());

        ConfigApplyResult rejected = provider.onConfigChanged(canonicalBytes(tampered));

        assertThat(rejected.status()).isEqualTo(ConfigApplyResult.Status.REJECTED);
        assertThat(rejected.errorCode()).isEqualTo("ENVELOPE_CHECKSUM_MISMATCH");
        assertThat(provider.current().configVersion()).isEqualTo(2);
        assertThat(provider.current().observedConfigVersion()).isEqualTo(3);
        assertThat(provider.current().mockConfigValid()).isFalse();
        assertThat(new RouteResolver().resolve(provider.current(), context("OTHER", "READ")).mode())
                .isEqualTo(MockMode.REAL);
        assertThatThrownBy(() -> new RouteResolver().resolve(provider.current(), context("PAY", "CREATE")))
                .isInstanceOf(MockConfigInvalidException.class);

        assertThat(provider.onConfigChanged(wrapper(4, "X-Business-V4", trustedKey, "trusted")).status())
                .isEqualTo(ConfigApplyResult.Status.APPLIED);
        assertThat(provider.current().mockConfigValid()).isTrue();
        assertThat(provider.current().route("PAY", "CREATE").allowedBusinessHeaders())
                .containsExactly("x-business-v4");
        assertThat(acknowledgements).extracting(SdkConfigAck::status)
                .containsExactly(SdkConfigAck.Status.APPLIED, SdkConfigAck.Status.REJECTED, SdkConfigAck.Status.APPLIED);
    }

    @Test
    void rejectsUnknownKeyAndExpiredEnvelope() throws Exception {
        SignedConfigActivationProvider unknownKeyProvider = provider(new ArrayList<SdkConfigAck>());

        ConfigApplyResult unknown = unknownKeyProvider.onConfigChanged(
                wrapper(2, "X-Business", unknownKey, "unknown"));

        assertThat(unknown.status()).isEqualTo(ConfigApplyResult.Status.REJECTED);
        assertThat(unknown.errorCode()).isEqualTo("SIGNATURE_KEY_UNKNOWN_OR_WEAK");
        assertThat(unknownKeyProvider.current().configVersion()).isEqualTo(1);

        SignedConfigActivationProvider expiredProvider = provider(new ArrayList<SdkConfigAck>());
        ObjectNode expired = (ObjectNode) mapper.readTree(wrapper(2, "X-Business", trustedKey, "trusted"));
        ((ObjectNode) expired.at("/activation/envelope/snapshot"))
                .put("expireAt", "2026-08-30T23:59:59Z");
        resignAll(expired, trustedKey, "trusted");

        ConfigApplyResult expiredResult = expiredProvider.onConfigChanged(canonicalBytes(expired));

        assertThat(expiredResult.errorCode()).isEqualTo("CONFIG_EXPIRED");
        assertThat(expiredProvider.current().configVersion()).isEqualTo(1);
    }

    @Test
    void rejectsMissingPolicyAndPolicyRouteDrift() throws Exception {
        SignedConfigActivationProvider missingProvider = provider(new ArrayList<SdkConfigAck>());
        ObjectNode missing = (ObjectNode) mapper.readTree(wrapper(2, "X-Business", trustedKey, "trusted"));
        ((ObjectNode) missing.at("/activation/envelope/snapshot/securityPolicyPayloads")).remove("11");
        resignAll(missing, trustedKey, "trusted");

        assertThat(missingProvider.onConfigChanged(canonicalBytes(missing)).errorCode())
                .isEqualTo("SECURITY_POLICY_SET_INVALID");

        SignedConfigActivationProvider fallbackProvider = provider(new ArrayList<SdkConfigAck>());
        ObjectNode missingFallback = (ObjectNode) mapper.readTree(
                wrapper(2, "X-Business", trustedKey, "trusted"));
        ((ObjectNode) missingFallback.at("/activation/envelope/snapshot/routing/providerRoutes/PAY"))
                .put("unavailablePolicy", "FALLBACK_REAL");
        resignAll(missingFallback, trustedKey, "trusted");

        assertThat(fallbackProvider.onConfigChanged(canonicalBytes(missingFallback)).errorCode())
                .isEqualTo("REQUIRED_SECURITY_POLICY_MISSING");

        SignedConfigActivationProvider driftProvider = provider(new ArrayList<SdkConfigAck>());
        ObjectNode drift = (ObjectNode) mapper.readTree(wrapper(2, "X-Business", trustedKey, "trusted"));
        ArrayNode headers = mapper.createArrayNode().add("X-Not-In-Policy");
        ((ObjectNode) drift.at("/activation/envelope/snapshot/routing/providerRoutes/PAY"))
                .set("allowedBusinessHeaders", headers);
        resignAll(drift, trustedKey, "trusted");

        assertThat(driftProvider.onConfigChanged(canonicalBytes(drift)).errorCode())
                .isEqualTo("HEADER_FILTER_POLICY_MISMATCH");
    }

    @Test
    void rejectsVersionRollbackWithoutInvalidatingCurrentSnapshot() throws Exception {
        SignedConfigActivationProvider provider = provider(new ArrayList<SdkConfigAck>());
        assertThat(provider.onConfigChanged(wrapper(3, "X-V3", trustedKey, "trusted")).status())
                .isEqualTo(ConfigApplyResult.Status.APPLIED);

        ConfigApplyResult result = provider.onConfigChanged(wrapper(2, "X-V2", trustedKey, "trusted"));

        assertThat(result.errorCode()).isEqualTo("CONFIG_VERSION_ROLLBACK");
        assertThat(provider.current().configVersion()).isEqualTo(3);
        assertThat(provider.current().mockConfigValid()).isTrue();
        assertThat(provider.current().route("PAY", "CREATE").allowedBusinessHeaders())
                .containsExactly("x-v3");
    }

    @Test
    void rejectsScopeMismatchAndSensitiveHeaderAllowlist() throws Exception {
        SignedConfigActivationProvider provider = provider(new ArrayList<SdkConfigAck>());
        ObjectNode wrongScope = (ObjectNode) mapper.readTree(wrapper(2, "X-Business", trustedKey, "trusted"));
        ((ObjectNode) wrongScope.get("activation")).put("appCode", "other-app");
        resignWrapperOnly(wrongScope, trustedKey.getPrivate());

        assertThat(provider.onConfigChanged(canonicalBytes(wrongScope)).errorCode())
                .isEqualTo("SCOPE_MISMATCH");
        assertThat(provider.current().mockConfigValid()).isTrue();

        ObjectNode sensitive = (ObjectNode) mapper.readTree(wrapper(2, "X-Business", trustedKey, "trusted"));
        ArrayNode authorization = mapper.createArrayNode().add("Authorization");
        ((ObjectNode) sensitive.at("/activation/envelope/snapshot/routing/providerRoutes/PAY"))
                .set("allowedBusinessHeaders", authorization);
        ((ObjectNode) sensitive.at("/activation/envelope/snapshot/securityPolicyPayloads/11"))
                .set("allowedBusinessHeaders", authorization.deepCopy());
        updatePolicyChecksum(sensitive, 11L);
        resignAll(sensitive, trustedKey, "trusted");

        assertThat(provider.onConfigChanged(canonicalBytes(sensitive)).errorCode())
                .isEqualTo("SENSITIVE_HEADER_ALLOW_FORBIDDEN");
    }

    @Test
    void productionRequiresHttpsRuntimeUri() throws Exception {
        RoutingSnapshot initial = RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("sample-app")
                .environment("PROD")
                .runtimeBaseUri(URI.create("https://runtime.example.test"))
                .defaultRoute(RouteConfig.real())
                .build();
        SignedConfigActivationProvider provider = new SignedConfigActivationProvider(
                "sample-app",
                "PROD",
                "sdk-instance-1",
                initial,
                keyId -> "trusted".equals(keyId) ? trustedKey.getPublic() : null,
                acknowledgement -> { },
                Clock.fixed(NOW, ZoneOffset.UTC));
        ObjectNode wrapper = (ObjectNode) mapper.readTree(
                wrapper(2, "X-Business", trustedKey, "trusted"));
        ((ObjectNode) wrapper.get("activation")).put("environment", "PROD");
        ObjectNode snapshot = (ObjectNode) wrapper.at("/activation/envelope/snapshot");
        snapshot.put("environment", "PROD");
        ((ObjectNode) snapshot.at("/routing/providerRoutes")).removeAll();
        ((ArrayNode) snapshot.get("securityPolicyRefs")).removeAll();
        ((ObjectNode) snapshot.get("securityPolicyPayloads")).removeAll();
        resignAll(wrapper, trustedKey, "trusted");

        ConfigApplyResult result = provider.onConfigChanged(canonicalBytes(wrapper));

        assertThat(result.errorCode()).isEqualTo("RUNTIME_URI_INSECURE_PRODUCTION");
        assertThat(provider.current().configVersion()).isEqualTo(initial.configVersion());
        assertThat(provider.current().route("ANY", "API").mode()).isEqualTo(MockMode.REAL);
    }

    @Test
    void appliedSnapshotExpiresAtRequestTimeWithoutBreakingRealRoutes() throws Exception {
        SignedConfigActivationProvider provider = provider(new ArrayList<SdkConfigAck>());
        assertThat(provider.onConfigChanged(wrapper(2, "X-Business", trustedKey, "trusted")).status())
                .isEqualTo(ConfigApplyResult.Status.APPLIED);
        RouteResolver resolverAfterExpiry = new RouteResolver(Clock.fixed(
                Instant.parse("2026-09-02T00:00:00Z"),
                ZoneOffset.UTC));

        assertThat(resolverAfterExpiry.resolve(provider.current(), context("OTHER", "READ")).mode())
                .isEqualTo(MockMode.REAL);
        assertThatThrownBy(() -> resolverAfterExpiry.resolve(provider.current(), context("PAY", "CREATE")))
                .isInstanceOf(MockConfigInvalidException.class)
                .hasMessageContaining("MOCK_CONFIG_INVALID");
    }

    @Test
    void headerFilterSwitchesWithTheSameSnapshotReference() throws Exception {
        SignedConfigActivationProvider provider = provider(new ArrayList<SdkConfigAck>());
        provider.onConfigChanged(wrapper(2, "X-Old", trustedKey, "trusted"));
        RoutingSnapshot before = provider.current();
        provider.onConfigChanged(wrapper(3, "X-New", trustedKey, "trusted"));
        RoutingSnapshot after = provider.current();

        Map<String, Collection<String>> original = new LinkedHashMap<String, Collection<String>>();
        original.put("X-Old", Collections.singletonList("old"));
        original.put("X-New", Collections.singletonList("new"));
        Map<String, List<String>> beforeHeaders = HeaderSanitizer.sanitize(
                original,
                before.route("PAY", "CREATE").allowedBusinessHeaders(),
                before.route("PAY", "CREATE").additionalSensitiveHeaders());
        Map<String, List<String>> afterHeaders = HeaderSanitizer.sanitize(
                original,
                after.route("PAY", "CREATE").allowedBusinessHeaders(),
                after.route("PAY", "CREATE").additionalSensitiveHeaders());

        assertThat(beforeHeaders).containsKey("X-Old").doesNotContainKey("X-New");
        assertThat(afterHeaders).containsKey("X-New").doesNotContainKey("X-Old");
        assertThat(before.configVersion()).isEqualTo(2);
        assertThat(after.configVersion()).isEqualTo(3);
    }

    @Test
    void ackReporterRetriesSameIdempotencyKeyAndStopsAtBound() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        ackExecutor = new ScheduledThreadPoolExecutor(1);
        RetryingSdkConfigAckReporter reporter = new RetryingSdkConfigAckReporter(
                acknowledgement -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("temporary");
                    }
                    assertThat(acknowledgement.idempotencyKey())
                            .isEqualTo("activation-2\u0000sdk-instance-1");
                    delivered.countDown();
                },
                ackExecutor,
                3,
                1L);

        reporter.report(ack("activation-2"));

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void signedProviderRejectsUnsignedMockBootstrap() {
        RoutingSnapshot unsafeInitial = RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("sample-app")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://localhost:19090"))
                .defaultRoute(com.xuntian.mock.client.core.routing.RouteConfig.mock(
                        com.xuntian.mock.client.core.model.UnavailablePolicy.FAST_FAIL))
                .build();

        assertThatThrownBy(() -> new SignedConfigActivationProvider(
                "sample-app",
                "TEST",
                "sdk-instance-1",
                unsafeInitial,
                keyId -> trustedKey.getPublic(),
                acknowledgement -> { },
                Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REAL");
    }

    private SignedConfigActivationProvider provider(List<SdkConfigAck> acknowledgements) {
        return new SignedConfigActivationProvider(
                "sample-app",
                "TEST",
                "sdk-instance-1",
                initialSnapshot(),
                keyId -> "trusted".equals(keyId) ? trustedKey.getPublic() : null,
                acknowledgements::add,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RoutingSnapshot initialSnapshot() {
        return RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("sample-app")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://localhost:19090"))
                .defaultRoute(RouteConfig.real())
                .build();
    }

    private MockContext context(String provider, String api) {
        return MockContext.builder()
                .appCode("sample-app")
                .environment("TEST")
                .provider(provider)
                .api(api)
                .mockRequestId("mr-sdk-test")
                .build();
    }

    private byte[] wrapper(long version, String allowedHeader, KeyPair keyPair, String keyId) throws Exception {
        ObjectNode headerPayload = mapper.createObjectNode();
        headerPayload.set("allowedBusinessHeaders", mapper.createArrayNode().add(allowedHeader));
        headerPayload.set("additionalSensitiveHeaders", mapper.createArrayNode().add("X-Legacy-Secret"));

        ObjectNode policyRef = mapper.createObjectNode();
        policyRef.put("policyVersionId", 11L);
        policyRef.put("policyType", "SDK_HEADER_FILTER");
        policyRef.put("scopeKey", "provider:PAY");
        policyRef.put("checksum", checksum(canonicalBytes(headerPayload)));

        ObjectNode routing = mapper.createObjectNode();
        routing.put("runtimeBaseUri", "http://localhost:19090");
        routing.put("allowRequestOverride", false);
        routing.set("defaultRoute", realRoute());
        ObjectNode providers = mapper.createObjectNode();
        providers.set("PAY", mockRoute(allowedHeader));
        routing.set("providerRoutes", providers);
        routing.set("apiRoutes", mapper.createObjectNode());

        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("schemaVersion", "1");
        snapshot.put("appCode", "sample-app");
        snapshot.put("environment", "TEST");
        snapshot.put("configVersion", version);
        snapshot.set("routing", routing);
        snapshot.set("securityPolicyRefs", mapper.createArrayNode().add(policyRef));
        ObjectNode payloads = mapper.createObjectNode();
        payloads.set("11", headerPayload);
        snapshot.set("securityPolicyPayloads", payloads);
        snapshot.put("effectiveAt", "2026-08-30T00:00:00Z");
        snapshot.put("expireAt", "2026-09-02T00:00:00Z");

        ObjectNode envelope = mapper.createObjectNode();
        envelope.set("snapshot", snapshot);
        envelope.put("checksum", checksum(canonicalBytes(snapshot)));
        envelope.put("signature", sign(canonicalBytes(snapshot), keyPair.getPrivate()));
        envelope.put("signatureKeyId", keyId);
        envelope.put("signatureAlgorithm", "SHA256withRSA");

        ObjectNode activation = mapper.createObjectNode();
        activation.put("schemaVersion", "1");
        activation.put("activationId", "activation-" + version);
        activation.put("appCode", "sample-app");
        activation.put("environment", "TEST");
        activation.put("envelopeId", Long.toString(1_000L + version));
        activation.put("configVersion", version);
        activation.put("envelopeChecksum", envelope.get("checksum").textValue());
        activation.put("publishedAt", "2026-08-31T00:00:00Z");
        activation.set("envelope", envelope);

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("activation", activation);
        wrapper.put("wrapperChecksum", checksum(canonicalBytes(activation)));
        wrapper.put("wrapperSignature", sign(canonicalBytes(activation), keyPair.getPrivate()));
        wrapper.put("wrapperSignatureKeyId", keyId);
        wrapper.put("wrapperSignatureAlgorithm", "SHA256withRSA");
        return canonicalBytes(wrapper);
    }

    private ObjectNode realRoute() {
        ObjectNode route = mapper.createObjectNode();
        route.put("mode", "REAL");
        route.put("unavailablePolicy", "FAST_FAIL");
        route.set("allowedBusinessHeaders", mapper.createArrayNode());
        route.set("additionalSensitiveHeaders", mapper.createArrayNode());
        route.set("allowedRealHosts", mapper.createArrayNode());
        return route;
    }

    private ObjectNode mockRoute(String header) {
        ObjectNode route = mapper.createObjectNode();
        route.put("mode", "MOCK");
        route.put("unavailablePolicy", "FAST_FAIL");
        route.set("allowedBusinessHeaders", mapper.createArrayNode().add(header));
        route.set("additionalSensitiveHeaders", mapper.createArrayNode().add("X-Legacy-Secret"));
        route.set("allowedRealHosts", mapper.createArrayNode());
        route.put("headerFilterPolicyVersionId", 11L);
        return route;
    }

    private void updatePolicyChecksum(ObjectNode wrapper, long policyVersion) throws Exception {
        JsonNode payload = wrapper.at(
                "/activation/envelope/snapshot/securityPolicyPayloads/" + policyVersion);
        ArrayNode refs = (ArrayNode) wrapper.at("/activation/envelope/snapshot/securityPolicyRefs");
        for (JsonNode ref : refs) {
            if (ref.path("policyVersionId").longValue() == policyVersion) {
                ((ObjectNode) ref).put("checksum", checksum(canonicalBytes(payload)));
            }
        }
    }

    private void resignAll(ObjectNode wrapper, KeyPair keyPair, String keyId) throws Exception {
        ObjectNode activation = (ObjectNode) wrapper.get("activation");
        ObjectNode envelope = (ObjectNode) activation.get("envelope");
        JsonNode snapshot = envelope.get("snapshot");
        envelope.put("checksum", checksum(canonicalBytes(snapshot)));
        envelope.put("signature", sign(canonicalBytes(snapshot), keyPair.getPrivate()));
        envelope.put("signatureKeyId", keyId);
        envelope.put("signatureAlgorithm", "SHA256withRSA");
        activation.put("envelopeChecksum", envelope.get("checksum").textValue());
        resignWrapperOnly(wrapper, keyPair.getPrivate());
        wrapper.put("wrapperSignatureKeyId", keyId);
    }

    private void resignWrapperOnly(ObjectNode wrapper, PrivateKey key) throws Exception {
        JsonNode activation = wrapper.get("activation");
        wrapper.put("wrapperChecksum", checksum(canonicalBytes(activation)));
        wrapper.put("wrapperSignature", sign(canonicalBytes(activation), key));
    }

    private SdkConfigAck ack(String activationId) {
        return new SdkConfigAck(
                activationId,
                "sample-app",
                "TEST",
                "sdk-instance-1",
                1_002L,
                1,
                2,
                Collections.singletonList(new SdkSecurityPolicyRef(
                        11L,
                        "SDK_HEADER_FILTER",
                        "provider:PAY",
                        "0000000000000000000000000000000000000000000000000000000000000000")),
                SdkConfigAck.Status.APPLIED,
                NOW,
                null);
    }

    private String sign(byte[] content, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(content);
        return java.util.Base64.getEncoder().encodeToString(signature.sign());
    }

    private String checksum(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private byte[] canonicalBytes(JsonNode node) throws Exception {
        return mapper.writeValueAsBytes(canonical(node));
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            Map<String, JsonNode> sorted = new TreeMap<String, JsonNode>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), field.getValue());
            }
            for (Map.Entry<String, JsonNode> field : sorted.entrySet()) {
                result.set(field.getKey(), canonical(field.getValue()));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode value : node) {
                result.add(canonical(value));
            }
            return result;
        }
        return node.deepCopy();
    }
}
