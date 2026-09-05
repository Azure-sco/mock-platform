package com.xuntian.mock.runtime.release;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ReleaseSnapshotCache {

    private final ConcurrentHashMap<String, VerifiedReleaseSnapshot> releases = new ConcurrentHashMap<>();

    public Optional<VerifiedReleaseSnapshot> find(String releaseId) {
        return Optional.ofNullable(releases.get(releaseId));
    }

    public VerifiedReleaseSnapshot put(VerifiedReleaseSnapshot candidate) {
        return releases.compute(candidate.releaseId(), (releaseId, existing) -> {
            if (existing == null) {
                return candidate;
            }
            if (!existing.checksum().equals(candidate.checksum())
                    || !existing.scope().equals(candidate.scope())
                    || !existing.signatureKeyId().equals(candidate.signatureKeyId())) {
                throw new SnapshotVerificationException(
                        SnapshotVerificationException.Reason.IMMUTABLE_RELEASE_CONFLICT,
                        "ReleaseId was observed with different immutable content");
            }
            return existing;
        });
    }
}
