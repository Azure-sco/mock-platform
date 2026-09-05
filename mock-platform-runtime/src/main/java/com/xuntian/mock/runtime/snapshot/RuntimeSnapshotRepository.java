package com.xuntian.mock.runtime.snapshot;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.release.PinnedRuntimeSnapshot;

import java.time.Instant;
import java.util.Optional;

public interface RuntimeSnapshotRepository {

    Optional<RuntimeSnapshot> find(String environment, String app);

    default RuntimeSnapshot require(String environment, String app) {
        return find(environment, app).orElseThrow(() -> new PlatformException(
                ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                "No valid Runtime Snapshot is available for this application"));
    }

    default PinnedRuntimeSnapshot requirePinned(String environment, String app, Instant requestTime) {
        RuntimeSnapshot snapshot = require(environment, app);
        return new PinnedRuntimeSnapshot(snapshot.releaseId(), 0, snapshot);
    }

    default PinnedRuntimeSnapshot requirePinnedRelease(
            String environment,
            String app,
            String releaseId) {
        RuntimeSnapshot current = require(environment, app);
        if (!current.releaseId().equals(releaseId)) {
            throw new PlatformException(
                    ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                    "The Flow's fixed Runtime Snapshot is not available");
        }
        return new PinnedRuntimeSnapshot(current.releaseId(), 0, current);
    }
}
