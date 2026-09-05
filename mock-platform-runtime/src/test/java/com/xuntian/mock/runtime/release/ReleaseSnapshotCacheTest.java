package com.xuntian.mock.runtime.release;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseSnapshotCacheTest {

    @Test
    void releaseIdCannotBeReusedForDifferentImmutableContent() {
        KeyPair keys = ReleaseTestData.keyPair();
        RuntimeSnapshotEnvelopeVerifier verifier = ReleaseTestData.verifier(ReleaseTestData.mapper(), keys);
        ReleaseTestData.Signed signed = ReleaseTestData.signed(
                verifier, keys.getPrivate(), ReleaseTestData.KEY_ID,
                ReleaseTestData.snapshot("rel-immutable"), 1);
        VerifiedReleaseSnapshot verified = verifier.verify(
                signed.envelopeBytes(), signed.pointer(), ReleaseTestData.SCOPE);
        ReleaseSnapshotCache cache = new ReleaseSnapshotCache();
        cache.put(verified);
        VerifiedReleaseSnapshot conflicting = new VerifiedReleaseSnapshot(
                verified.scope(), verified.releaseId(), "f".repeat(64),
                verified.signatureKeyId(), verified.snapshot());

        assertThatThrownBy(() -> cache.put(conflicting))
                .isInstanceOfSatisfying(SnapshotVerificationException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.reason())
                                .isEqualTo(SnapshotVerificationException.Reason.IMMUTABLE_RELEASE_CONFLICT));
    }
}
