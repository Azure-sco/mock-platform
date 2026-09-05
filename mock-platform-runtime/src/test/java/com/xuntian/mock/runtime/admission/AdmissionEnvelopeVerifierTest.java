package com.xuntian.mock.runtime.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.identity.RuntimeIdentity;
import com.xuntian.mock.runtime.release.SnapshotSignatureKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionEnvelopeVerifierTest {

    private static final String KEY_ID = "test-rsa";
    private final ObjectMapper mapper = new ObjectMapper();
    private KeyPair keyPair;
    private AdmissionEnvelopeVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        SnapshotSignatureKeyProvider keys = keyId -> KEY_ID.equals(keyId)
                ? Optional.of(keyPair.getPublic())
                : Optional.empty();
        verifier = new AdmissionEnvelopeVerifier(mapper, keys);
    }

    @Test
    void verifiesCanonicalLeaseAndRegistryFailsClosedAfterExpiry() throws Exception {
        Instant now = Instant.parse("2026-08-31T06:00:00Z");
        VerifiedAdmissionSnapshot snapshot = verifier.verify(
                envelope("TEST", "sample-app", now, now.plusSeconds(60)),
                new AdmissionScope("TEST", "sample-app"),
                now);

        AdmissionSnapshotRegistry registry = new AdmissionSnapshotRegistry();
        assertThat(registry.apply(snapshot)).isEqualTo(AdmissionSnapshotRegistry.ApplyResult.APPLIED);
        registry.authorize(
                new RuntimeIdentity("sample-app", "TEST", "tenant-a", "account-a"),
                "oa", "apply.create", now.plusSeconds(10));

        assertThatThrownBy(() -> registry.authorize(
                new RuntimeIdentity("sample-app", "TEST", "tenant-b", "account-a"),
                "oa", "apply.create", now.plusSeconds(10)))
                .isInstanceOfSatisfying(PlatformException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.MOCK_FORBIDDEN));
        assertThatThrownBy(() -> registry.authorize(
                new RuntimeIdentity("sample-app", "TEST", "tenant-a", "account-a"),
                "oa", "apply.create", now.plusSeconds(61)))
                .isInstanceOfSatisfying(PlatformException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.MOCK_ADMISSION_POLICY_STALE));
    }

    @Test
    void rejectsTamperUnknownKeyScopeAndClockViolations() throws Exception {
        Instant now = Instant.parse("2026-08-31T06:00:00Z");
        byte[] valid = envelope("TEST", "sample-app", now, now.plusSeconds(60));
        JsonNode tampered = mapper.readTree(valid);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered).put("bindingVersion", 2);

        assertReason(CanonicalJson.write(tampered), now, AdmissionVerificationException.Reason.CHECKSUM_MISMATCH);
        assertThatThrownBy(() -> verifier.verify(
                valid, new AdmissionScope("TEST", "other-app"), now))
                .isInstanceOfSatisfying(AdmissionVerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AdmissionVerificationException.Reason.SCOPE_MISMATCH));
        assertReason(envelope("TEST", "sample-app", now.plusSeconds(6), now.plusSeconds(60)),
                now, AdmissionVerificationException.Reason.CLOCK_SKEW);
        assertReason(envelope("TEST", "sample-app", now.minusSeconds(61), now.minusSeconds(1)),
                now, AdmissionVerificationException.Reason.EXPIRED);

        JsonNode unknown = mapper.readTree(valid);
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).put("signatureKeyId", "unknown-key");
        assertReason(CanonicalJson.write(unknown), now, AdmissionVerificationException.Reason.UNKNOWN_KEY);
    }

    @Test
    void ignoresOlderRenewalWithoutExtendingTheLease() throws Exception {
        Instant now = Instant.parse("2026-08-31T06:00:00Z");
        AdmissionSnapshotRegistry registry = new AdmissionSnapshotRegistry();
        VerifiedAdmissionSnapshot current = verifier.verify(
                envelope("TEST", "sample-app", now, now.plusSeconds(60)),
                new AdmissionScope("TEST", "sample-app"), now);
        VerifiedAdmissionSnapshot stale = verifier.verify(
                envelope("TEST", "sample-app", now.minusSeconds(1), now.plusSeconds(40)),
                new AdmissionScope("TEST", "sample-app"), now);

        registry.apply(current);
        assertThat(registry.apply(stale)).isEqualTo(AdmissionSnapshotRegistry.ApplyResult.STALE_IGNORED);
        assertThat(registry.current(current.scope()).orElseThrow().notAfter()).isEqualTo(now.plusSeconds(60));
    }

    private void assertReason(byte[] bytes, Instant now, AdmissionVerificationException.Reason reason) {
        assertThatThrownBy(() -> verifier.verify(bytes, new AdmissionScope("TEST", "sample-app"), now))
                .isInstanceOfSatisfying(AdmissionVerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private byte[] envelope(
            String environment,
            String appCode,
            Instant issuedAt,
            Instant notAfter) throws Exception {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("providerCode", "oa");
        rule.put("apiCodes", List.of("apply.create"));
        rule.put("tenantCodes", List.of("tenant-a"));
        rule.put("testAccounts", List.of("account-a"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("environment", environment);
        payload.put("appCode", appCode);
        payload.put("rules", List.of(rule));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("bindingId", "binding-1");
        content.put("environment", environment);
        content.put("appCode", appCode);
        content.put("policyVersionId", 10L);
        content.put("bindingVersion", 1L);
        content.put("payload", payload);
        content.put("policyChecksum", sha256(CanonicalJson.write(payload)));
        content.put("issuedAt", issuedAt.toString());
        content.put("notAfter", notAfter.toString());
        byte[] contentBytes = CanonicalJson.write(content);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(contentBytes);
        Map<String, Object> result = new LinkedHashMap<>(content);
        result.put("checksum", sha256(contentBytes));
        result.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        result.put("signatureKeyId", KEY_ID);
        result.put("signatureAlgorithm", "SHA256withRSA");
        return CanonicalJson.write(result);
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
