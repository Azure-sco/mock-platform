package com.xuntian.mock.runtime.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.runtime.snapshot.FixtureDefinition;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshotCompiler;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

final class ReleaseTestData {

    static final String APP = "sample-jdk17";
    static final ReleaseScope SCOPE = new ReleaseScope("TEST", APP);
    static final String KEY_ID = "release-key-1";

    private ReleaseTestData() {
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static RuntimeSnapshotEnvelopeVerifier verifier(ObjectMapper mapper, KeyPair keyPair) {
        return new RuntimeSnapshotEnvelopeVerifier(
                mapper,
                new RuntimeSnapshotCompiler(mapper),
                keyId -> KEY_ID.equals(keyId)
                        ? java.util.Optional.of(keyPair.getPublic())
                        : java.util.Optional.empty());
    }

    static PublishedSnapshotDefinition snapshot(String releaseId) {
        ObjectMapper mapper = mapper();
        try (InputStream input = ReleaseTestData.class.getResourceAsStream("/runtime-fixture.json")) {
            FixtureDefinition fixture = mapper.readValue(input, FixtureDefinition.class);
            List<FixtureDefinition.ScenarioDefinition> scenarios = fixture.scenarios().stream()
                    .map(scenario -> new FixtureDefinition.ScenarioDefinition(
                            scenario.scenarioId(),
                            scenario.scenarioVersionId(),
                            scenario.scenarioCode(),
                            scenario.provider(),
                            scenario.api(),
                            scenario.priority(),
                            scenario.effectiveFrom(),
                            scenario.effectiveTo(),
                            new FixtureDefinition.ScopeDefinition(
                                    List.of("TEST"),
                                    List.of(APP),
                                    scenario.scope().tenants(),
                                    scenario.scope().testAccounts()),
                            scenario.matchRules(),
                            scenario.response()))
                    .toList();
            return new PublishedSnapshotDefinition(
                    PublishedSnapshotDefinition.LEGACY_SCHEMA_VERSION,
                    releaseId,
                    "TEST",
                    APP,
                    Instant.parse("2026-08-31T00:00:00Z"),
                    new CompiledArtifactManifest(
                            CompiledArtifactManifest.CONTRACT_V1,
                            CompiledArtifactManifest.MATCHER_V1,
                            CompiledArtifactManifest.TEMPLATE_V1),
                    fixture.contracts(),
                    scenarios);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Signed signed(
            RuntimeSnapshotEnvelopeVerifier verifier,
            PrivateKey privateKey,
            String keyId,
            PublishedSnapshotDefinition snapshot,
            long activationVersion) {
        String checksum = verifier.checksum(snapshot);
        String signature = sign(privateKey, verifier.canonicalSnapshotBytes(snapshot));
        SignedRuntimeSnapshotEnvelope envelope = new SignedRuntimeSnapshotEnvelope(
                snapshot,
                checksum,
                signature,
                keyId,
                RuntimeSnapshotEnvelopeVerifier.SIGNATURE_ALGORITHM);
        byte[] envelopeBytes = verifier.canonicalEnvelopeBytes(envelope);
        ActiveReleasePointer pointer = new ActiveReleasePointer(
                snapshot.releaseId(), activationVersion, checksum, keyId);
        return new Signed(pointer, envelopeBytes);
    }

    private static String sign(PrivateKey privateKey, byte[] canonical) {
        try {
            Signature signer = Signature.getInstance(RuntimeSnapshotEnvelopeVerifier.SIGNATURE_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(canonical);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    record Signed(ActiveReleasePointer pointer, byte[] envelopeBytes) {
    }
}
