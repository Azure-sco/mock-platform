package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.snapshot.RuntimeSnapshot;

public record PinnedRuntimeSnapshot(
        String releaseId,
        long activationVersion,
        String snapshotChecksum,
        RuntimeSnapshot snapshot) {

    public PinnedRuntimeSnapshot {
        if (releaseId == null || snapshot == null || !releaseId.equals(snapshot.releaseId())
                || activationVersion < 0 || snapshotChecksum == null
                || !snapshotChecksum.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Pinned Runtime Snapshot is invalid");
        }
    }

    public PinnedRuntimeSnapshot(String releaseId, long activationVersion, RuntimeSnapshot snapshot) {
        this(releaseId, activationVersion, "0".repeat(64), snapshot);
    }
}
