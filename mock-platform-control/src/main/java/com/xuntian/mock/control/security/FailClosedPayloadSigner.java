package com.xuntian.mock.control.security;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedPayloadSigner implements PayloadSigner {

    @Override
    public SignatureValue sign(byte[] canonicalPayload) {
        throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Production signing KMS adapter is not configured");
    }
}
