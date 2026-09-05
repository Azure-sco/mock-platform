package com.xuntian.mock.runtime.snapshot;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.release.LocalActiveReleaseRegistry;
import com.xuntian.mock.runtime.release.PinnedRuntimeSnapshot;
import com.xuntian.mock.runtime.release.ReleaseCandidate;
import com.xuntian.mock.runtime.release.ReleaseRecoveryPort;
import com.xuntian.mock.runtime.release.ReleaseScope;
import com.xuntian.mock.runtime.release.ReleaseSnapshotCache;
import com.xuntian.mock.runtime.release.RuntimeSnapshotEnvelopeVerifier;
import com.xuntian.mock.runtime.release.VerifiedReleaseSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@Profile("!local & !test")
public final class PublishedRuntimeSnapshotRepository implements RuntimeSnapshotRepository {

    private final LocalActiveReleaseRegistry activeReleases;
    private final ReleaseRecoveryPort recovery;
    private final RuntimeSnapshotEnvelopeVerifier verifier;
    private final ReleaseSnapshotCache cache;

    public PublishedRuntimeSnapshotRepository(
            LocalActiveReleaseRegistry activeReleases,
            ReleaseRecoveryPort recovery,
            RuntimeSnapshotEnvelopeVerifier verifier,
            ReleaseSnapshotCache cache) {
        this.activeReleases = activeReleases;
        this.recovery = recovery;
        this.verifier = verifier;
        this.cache = cache;
    }

    @Override
    public Optional<RuntimeSnapshot> find(String environment, String app) {
        return activeReleases.pin(new ReleaseScope(environment, app), Instant.now())
                .map(PinnedRuntimeSnapshot::snapshot);
    }

    @Override
    public PinnedRuntimeSnapshot requirePinned(String environment, String app, Instant requestTime) {
        return activeReleases.pin(new ReleaseScope(environment, app), requestTime)
                .orElseThrow(() -> new PlatformException(
                        ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                        "No valid published Runtime Snapshot is available for this application"));
    }

    @Override
    public PinnedRuntimeSnapshot requirePinnedRelease(String environment, String app, String releaseId) {
        ReleaseScope scope = new ReleaseScope(environment, app);
        Optional<PinnedRuntimeSnapshot> retained = activeReleases.pinRelease(scope, releaseId);
        if (retained.isPresent()) return retained.get();
        Optional<VerifiedReleaseSnapshot> cached = cache.find(releaseId)
                .filter(value -> value.scope().equals(scope));
        if (cached.isPresent()) {
            ReleaseCandidate candidate = recovery.recoverRelease(scope, releaseId)
                    .orElseThrow(() -> unavailable());
            VerifiedReleaseSnapshot release = verifier.verify(
                    candidate.envelopeBytes(), candidate.pointer(), scope);
            activeReleases.retain(scope, candidate.pointer(), release);
            return activeReleases.pinRelease(scope, releaseId).orElseThrow();
        }
        ReleaseCandidate candidate = recovery.recoverRelease(scope, releaseId)
                .orElseThrow(() -> unavailable());
        VerifiedReleaseSnapshot verified = cache.put(verifier.verify(
                candidate.envelopeBytes(), candidate.pointer(), scope));
        activeReleases.retain(scope, candidate.pointer(), verified);
        return activeReleases.pinRelease(scope, releaseId).orElseThrow();
    }

    private static PlatformException unavailable() {
        return new PlatformException(
                ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                "The Flow's fixed Runtime Snapshot is not available");
    }
}
