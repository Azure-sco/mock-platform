package com.xuntian.mock.runtime.release;

import java.security.PublicKey;
import java.util.Optional;

public interface SnapshotSignatureKeyProvider {

    Optional<PublicKey> find(String keyId);
}
