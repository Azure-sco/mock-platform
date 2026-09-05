package com.xuntian.mock.runtime.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeSnapshotEnvelopeVerifierTest {

    private KeyPair keyPair;
    private RuntimeSnapshotEnvelopeVerifier verifier;

    @BeforeEach
    void setUp() {
        keyPair = ReleaseTestData.keyPair();
        verifier = ReleaseTestData.verifier(ReleaseTestData.mapper(), keyPair);
    }

    @Test
    void verifiesChecksumSignatureScopeSchemaAndCompiledArtifacts() {
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, keyPair.getPrivate(), ReleaseTestData.KEY_ID,
                ReleaseTestData.snapshot("rel-verified"), 7);

        VerifiedReleaseSnapshot verified = verifier.verify(
                signed.envelopeBytes(), signed.pointer(), ReleaseTestData.SCOPE);

        assertThat(verified.releaseId()).isEqualTo("rel-verified");
        assertThat(verified.snapshot().apis()).hasSize(4);
        assertThat(verified.checksum()).isEqualTo(signed.pointer().snapshotChecksum());
    }

    @Test
    void rejectsTamperedCanonicalSnapshot() {
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, keyPair.getPrivate(), ReleaseTestData.KEY_ID,
                ReleaseTestData.snapshot("rel-tampered"), 8);
        byte[] tampered = new String(signed.envelopeBytes(), StandardCharsets.UTF_8)
                .replace("\"httpStatus\":200", "\"httpStatus\":201")
                .getBytes(StandardCharsets.UTF_8);

        assertFailure(tampered, signed.pointer(), SnapshotVerificationException.Reason.CHECKSUM_MISMATCH);
    }

    @Test
    void rejectsUnknownSignatureKey() {
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, keyPair.getPrivate(), "unknown-key",
                ReleaseTestData.snapshot("rel-unknown-key"), 9);

        assertFailure(signed.envelopeBytes(), signed.pointer(), SnapshotVerificationException.Reason.UNKNOWN_KEY);
    }

    @Test
    void rejectsInvalidSignatureFromKnownKeyId() {
        KeyPair attacker = ReleaseTestData.keyPair();
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, attacker.getPrivate(), ReleaseTestData.KEY_ID,
                ReleaseTestData.snapshot("rel-invalid-signature"), 12);

        assertFailure(signed.envelopeBytes(), signed.pointer(), SnapshotVerificationException.Reason.SIGNATURE_INVALID);
    }

    @Test
    void rejectsSnapshotOutsideRequestedScope() {
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, keyPair.getPrivate(), ReleaseTestData.KEY_ID,
                ReleaseTestData.snapshot("rel-wrong-scope"), 13);

        assertThatThrownBy(() -> verifier.verify(
                signed.envelopeBytes(), signed.pointer(), new ReleaseScope("TEST", "another-app")))
                .isInstanceOfSatisfying(SnapshotVerificationException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(SnapshotVerificationException.Reason.SCOPE_MISMATCH));
    }

    @Test
    void rejectsUnsupportedSnapshotSchemaVersion() {
        PublishedSnapshotDefinition valid = ReleaseTestData.snapshot("rel-schema-two");
        PublishedSnapshotDefinition unsupported = new PublishedSnapshotDefinition(
                "2", valid.releaseId(), valid.environment(), valid.app(), valid.createdAt(),
                valid.compiledArtifacts(), valid.compiledContracts(), valid.compiledScenarios());
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, keyPair.getPrivate(), ReleaseTestData.KEY_ID, unsupported, 14);

        assertFailure(signed.envelopeBytes(), signed.pointer(), SnapshotVerificationException.Reason.UNSUPPORTED_SCHEMA);
    }

    @Test
    void rejectsMissingRequiredCompiledArtifactsEvenWhenCorrectlySigned() {
        PublishedSnapshotDefinition valid = ReleaseTestData.snapshot("rel-missing-artifacts");
        PublishedSnapshotDefinition missing = new PublishedSnapshotDefinition(
                valid.schemaVersion(), valid.releaseId(), valid.environment(), valid.app(), valid.createdAt(),
                null, valid.compiledContracts(), valid.compiledScenarios());
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, keyPair.getPrivate(), ReleaseTestData.KEY_ID, missing, 10);

        assertFailure(signed.envelopeBytes(), signed.pointer(), SnapshotVerificationException.Reason.INVALID_ENVELOPE);
    }

    @Test
    void rejectsNonCanonicalOuterEnvelope() {
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, keyPair.getPrivate(), ReleaseTestData.KEY_ID,
                ReleaseTestData.snapshot("rel-noncanonical"), 11);
        byte[] padded = (" \n" + new String(signed.envelopeBytes(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);

        assertFailure(padded, signed.pointer(), SnapshotVerificationException.Reason.INVALID_ENVELOPE);
    }

    private void assertFailure(
            byte[] envelope,
            ActiveReleasePointer pointer,
            SnapshotVerificationException.Reason reason) {
        assertThatThrownBy(() -> verifier.verify(envelope, pointer, ReleaseTestData.SCOPE))
                .isInstanceOfSatisfying(SnapshotVerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
