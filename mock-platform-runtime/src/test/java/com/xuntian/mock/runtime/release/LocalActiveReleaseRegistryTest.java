package com.xuntian.mock.runtime.release;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalActiveReleaseRegistryTest {

    @Test
    void atomicallyPinsReleaseAndActivationVersionAndExpiresLkg() {
        KeyPair keys = ReleaseTestData.keyPair();
        RuntimeSnapshotEnvelopeVerifier verifier = ReleaseTestData.verifier(ReleaseTestData.mapper(), keys);
        VerifiedReleaseSnapshot releaseOne = verified(verifier, keys, "rel-one", 1);
        VerifiedReleaseSnapshot releaseTwo = verified(verifier, keys, "rel-two", 2);
        ActiveReleasePointer pointerOne = pointer(verifier, "rel-one", 1);
        ActiveReleasePointer pointerTwo = pointer(verifier, "rel-two", 2);
        Instant loadedAt = Instant.parse("2026-08-31T00:00:00Z");
        LocalActiveReleaseRegistry registry = new LocalActiveReleaseRegistry(Duration.ofMinutes(10));

        assertThat(registry.activate(ReleaseTestData.SCOPE, pointerOne, releaseOne, loadedAt))
                .isEqualTo(LocalActiveReleaseRegistry.ActivationResult.ACTIVATED);
        assertThat(registry.activate(ReleaseTestData.SCOPE, pointerTwo, releaseTwo, loadedAt.plusSeconds(1)))
                .isEqualTo(LocalActiveReleaseRegistry.ActivationResult.ACTIVATED);

        PinnedRuntimeSnapshot pinned = registry.pin(ReleaseTestData.SCOPE, loadedAt.plusSeconds(2)).orElseThrow();
        assertThat(pinned.releaseId()).isEqualTo("rel-two");
        assertThat(pinned.activationVersion()).isEqualTo(2);
        assertThat(registry.pin(ReleaseTestData.SCOPE, loadedAt.plusSeconds(602))).isEmpty();
        PinnedRuntimeSnapshot fixedFlowRelease = registry
                .pinRelease(ReleaseTestData.SCOPE, "rel-one").orElseThrow();
        assertThat(fixedFlowRelease.releaseId()).isEqualTo("rel-one");
        assertThat(fixedFlowRelease.activationVersion()).isEqualTo(1);
        assertThat(fixedFlowRelease.snapshotChecksum()).isEqualTo(releaseOne.checksum());
    }

    @Test
    void rejectsConflictingPointerAtSameActivationVersion() {
        KeyPair keys = ReleaseTestData.keyPair();
        RuntimeSnapshotEnvelopeVerifier verifier = ReleaseTestData.verifier(ReleaseTestData.mapper(), keys);
        VerifiedReleaseSnapshot releaseOne = verified(verifier, keys, "rel-one", 1);
        VerifiedReleaseSnapshot releaseTwo = verified(verifier, keys, "rel-two", 1);
        LocalActiveReleaseRegistry registry = new LocalActiveReleaseRegistry(Duration.ofMinutes(10));
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        registry.activate(ReleaseTestData.SCOPE, pointer(verifier, "rel-one", 1), releaseOne, now);

        assertThatThrownBy(() -> registry.activate(
                ReleaseTestData.SCOPE, pointer(verifier, "rel-two", 1), releaseTwo, now.plusSeconds(1)))
                .isInstanceOfSatisfying(SnapshotVerificationException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(SnapshotVerificationException.Reason.POINTER_INVALID));
    }

    @Test
    void retainsRecoveredHistoricalReleaseWithItsActivationVersion() {
        KeyPair keys = ReleaseTestData.keyPair();
        RuntimeSnapshotEnvelopeVerifier verifier = ReleaseTestData.verifier(ReleaseTestData.mapper(), keys);
        VerifiedReleaseSnapshot release = verified(verifier, keys, "rel-recovered", 7);
        ActiveReleasePointer pointer = pointer(verifier, "rel-recovered", 7);
        LocalActiveReleaseRegistry registry = new LocalActiveReleaseRegistry(Duration.ofMinutes(10));

        registry.retain(ReleaseTestData.SCOPE, pointer, release);

        assertThat(registry.pinRelease(ReleaseTestData.SCOPE, "rel-recovered"))
                .get()
                .extracting(PinnedRuntimeSnapshot::activationVersion)
                .isEqualTo(7L);
    }

    private static VerifiedReleaseSnapshot verified(
            RuntimeSnapshotEnvelopeVerifier verifier,
            KeyPair keys,
            String releaseId,
            long version) {
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, keys.getPrivate(), ReleaseTestData.KEY_ID,
                ReleaseTestData.snapshot(releaseId), version);
        return verifier.verify(signed.envelopeBytes(), signed.pointer(), ReleaseTestData.SCOPE);
    }

    private static ActiveReleasePointer pointer(
            RuntimeSnapshotEnvelopeVerifier verifier,
            String releaseId,
            long version) {
        PublishedSnapshotDefinition snapshot = ReleaseTestData.snapshot(releaseId);
        return new ActiveReleasePointer(releaseId, version, verifier.checksum(snapshot), ReleaseTestData.KEY_ID);
    }
}
