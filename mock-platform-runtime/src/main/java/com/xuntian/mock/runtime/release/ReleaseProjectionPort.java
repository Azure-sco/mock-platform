package com.xuntian.mock.runtime.release;

import java.util.Optional;

public interface ReleaseProjectionPort {

    Optional<ActiveReleasePointer> loadPointer(ReleaseScope scope);

    Optional<byte[]> loadEnvelope(String releaseId);

    void cacheRecovered(ReleaseScope scope, ReleaseCandidate candidate);
}
