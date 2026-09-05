package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseRefreshCoordinatorTest {

    private KeyPair keys;
    private RuntimeSnapshotEnvelopeVerifier verifier;
    private FakeProjection projection;
    private FakeRecovery recovery;
    private RecordingAcks acks;
    private LocalActiveReleaseRegistry registry;
    private ReleaseRefreshCoordinator coordinator;

    @BeforeEach
    void setUp() {
        keys = ReleaseTestData.keyPair();
        verifier = ReleaseTestData.verifier(ReleaseTestData.mapper(), keys);
        projection = new FakeProjection();
        recovery = new FakeRecovery();
        acks = new RecordingAcks();
        registry = new LocalActiveReleaseRegistry(Duration.ofMinutes(10));
        RuntimeProperties properties = new RuntimeProperties();
        properties.setRuntimeNodeId("runtime-node-1");
        coordinator = new ReleaseRefreshCoordinator(
                projection,
                recovery,
                acks,
                verifier,
                new ReleaseSnapshotCache(),
                registry,
                properties);
    }

    @Test
    void fullyVerifiesBeforeAtomicSwitchAndKeepsLkgOnFailureWithFailedAck() {
        Instant firstLoadedAt = Instant.parse("2026-08-31T00:00:00Z");
        ReleaseTestData.Signed releaseOne = signed("rel-one", 1);
        projection.candidate = releaseOne;

        assertThat(coordinator.refresh(ReleaseTestData.SCOPE, firstLoadedAt).status())
                .isEqualTo(ReleaseRefreshCoordinator.Status.READY);
        assertThat(coordinator.refresh(ReleaseTestData.SCOPE, firstLoadedAt.plusSeconds(59)).status())
                .isEqualTo(ReleaseRefreshCoordinator.Status.READY);

        ReleaseTestData.Signed validReleaseTwo = signed("rel-two", 2);
        byte[] tampered = new String(validReleaseTwo.envelopeBytes(), StandardCharsets.UTF_8)
                .replace("\"httpStatus\":200", "\"httpStatus\":201")
                .getBytes(StandardCharsets.UTF_8);
        projection.candidate = new ReleaseTestData.Signed(validReleaseTwo.pointer(), tampered);

        ReleaseRefreshCoordinator.RefreshResult failed = coordinator.refresh(
                ReleaseTestData.SCOPE, firstLoadedAt.plusSeconds(60));

        assertThat(failed.status()).isEqualTo(ReleaseRefreshCoordinator.Status.FAILED_LKG_RETAINED);
        assertThat(failed.failureReason()).isEqualTo(SnapshotVerificationException.Reason.CHECKSUM_MISMATCH);
        PinnedRuntimeSnapshot lkg = registry.pin(
                ReleaseTestData.SCOPE, firstLoadedAt.plusSeconds(60)).orElseThrow();
        assertThat(lkg.releaseId()).isEqualTo("rel-one");
        assertThat(lkg.activationVersion()).isEqualTo(1);
        assertThat(acks.values.get(2).status()).isEqualTo(ActivationAck.Status.FAILED);
        assertThat(acks.values.get(2).errorMasked()).isEqualTo("CHECKSUM_MISMATCH");

        projection.candidate = validReleaseTwo;
        assertThat(coordinator.refresh(ReleaseTestData.SCOPE, firstLoadedAt.plusSeconds(61)).status())
                .isEqualTo(ReleaseRefreshCoordinator.Status.READY);
        PinnedRuntimeSnapshot switched = registry.pin(
                ReleaseTestData.SCOPE, firstLoadedAt.plusSeconds(61)).orElseThrow();
        assertThat(switched.releaseId()).isEqualTo("rel-two");
        assertThat(switched.activationVersion()).isEqualTo(2);
        assertThat(acks.values.get(3).status()).isEqualTo(ActivationAck.Status.READY);
    }

    @Test
    void expiresLastKnownGoodAfterTenMinutesWithoutSourceConfirmation() {
        Instant loadedAt = Instant.parse("2026-08-31T00:00:00Z");
        projection.candidate = signed("rel-lkg", 4);
        coordinator.refresh(ReleaseTestData.SCOPE, loadedAt);
        projection.candidate = null;

        assertThat(coordinator.refresh(ReleaseTestData.SCOPE, loadedAt.plusSeconds(1)).status())
                .isEqualTo(ReleaseRefreshCoordinator.Status.FAILED_LKG_RETAINED);
        assertThat(registry.pin(ReleaseTestData.SCOPE, loadedAt.plusSeconds(600))).isPresent();
        assertThat(registry.pin(ReleaseTestData.SCOPE, loadedAt.plusSeconds(601))).isEmpty();
    }

    @Test
    void recoversAuthoritativeReleaseFromMysqlAndRebuildsRedisProjection() {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        ReleaseTestData.Signed recovered = signed("rel-recovered", 12);
        recovery.candidate = recovered;

        ReleaseRefreshCoordinator.RefreshResult result = coordinator.refresh(ReleaseTestData.SCOPE, now);

        assertThat(result.status()).isEqualTo(ReleaseRefreshCoordinator.Status.READY_RECOVERED);
        assertThat(projection.cached).isNotNull();
        assertThat(projection.cached.pointer()).isEqualTo(recovered.pointer());
        assertThat(registry.pin(ReleaseTestData.SCOPE, now).orElseThrow().activationVersion()).isEqualTo(12);
        assertThat(acks.values).singleElement().satisfies(ack -> {
            assertThat(ack.status()).isEqualTo(ActivationAck.Status.READY);
            assertThat(ack.releaseId()).isEqualTo("rel-recovered");
        });
    }

    private ReleaseTestData.Signed signed(String releaseId, long version) {
        return ReleaseTestData.signed(
                verifier,
                keys.getPrivate(),
                ReleaseTestData.KEY_ID,
                ReleaseTestData.snapshot(releaseId),
                version);
    }

    private static final class FakeProjection implements ReleaseProjectionPort {
        private ReleaseTestData.Signed candidate;
        private ReleaseCandidate cached;

        @Override
        public Optional<ActiveReleasePointer> loadPointer(ReleaseScope scope) {
            return candidate == null ? Optional.empty() : Optional.of(candidate.pointer());
        }

        @Override
        public Optional<byte[]> loadEnvelope(String releaseId) {
            return candidate == null ? Optional.empty() : Optional.of(candidate.envelopeBytes());
        }

        @Override
        public void cacheRecovered(ReleaseScope scope, ReleaseCandidate candidate) {
            this.cached = candidate;
        }
    }

    private static final class FakeRecovery implements ReleaseRecoveryPort {
        private ReleaseTestData.Signed candidate;

        @Override
        public Optional<ReleaseCandidate> recover(ReleaseScope scope) {
            return candidate == null
                    ? Optional.empty()
                    : Optional.of(new ReleaseCandidate(candidate.pointer(), candidate.envelopeBytes()));
        }
    }

    private static final class RecordingAcks implements ActivationAckPort {
        private final List<ActivationAck> values = new ArrayList<>();

        @Override
        public void record(ActivationAck ack) {
            values.add(ack);
        }
    }
}
