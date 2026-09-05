package com.xuntian.mock.runtime.release;

import java.util.Optional;

public interface ReleaseRecoveryPort {

    Optional<ReleaseCandidate> recover(ReleaseScope scope);

    default Optional<ReleaseCandidate> recoverRelease(ReleaseScope scope, String releaseId) {
        return Optional.empty();
    }
}
