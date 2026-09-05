package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedRuntimeSnapshotSigner implements RuntimeSnapshotSigner {

    @Override
    public SignatureValue sign(byte[] canonicalSnapshot) {
        throw missing();
    }

    @Override
    public void verify(byte[] canonicalSnapshot, byte[] signature, String keyId, String algorithm) {
        throw missing();
    }

    private PlatformException missing() {
        return new PlatformException(
                ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                "Production Release signing KMS adapter is not configured");
    }
}
