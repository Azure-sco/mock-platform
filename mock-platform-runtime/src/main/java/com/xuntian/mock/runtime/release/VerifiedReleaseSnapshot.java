package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.snapshot.RuntimeSnapshot;

public record VerifiedReleaseSnapshot(
        ReleaseScope scope,
        String releaseId,
        String checksum,
        String signatureKeyId,
        RuntimeSnapshot snapshot) {

    public VerifiedReleaseSnapshot {
        if (scope == null || snapshot == null || releaseId == null || checksum == null || signatureKeyId == null) {
            throw new IllegalArgumentException("Verified Release fields are required");
        }
        if (!releaseId.equals(snapshot.releaseId())
                || !scope.environment().equals(snapshot.environment())
                || !scope.app().equals(snapshot.app())) {
            throw new IllegalArgumentException("Verified Release scope does not match Runtime Snapshot");
        }
    }
}
