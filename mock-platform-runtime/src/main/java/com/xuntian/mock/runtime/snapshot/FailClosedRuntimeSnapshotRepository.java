package com.xuntian.mock.runtime.snapshot;

import java.util.Optional;

public final class FailClosedRuntimeSnapshotRepository implements RuntimeSnapshotRepository {

    @Override
    public Optional<RuntimeSnapshot> find(String environment, String app) {
        return Optional.empty();
    }
}
