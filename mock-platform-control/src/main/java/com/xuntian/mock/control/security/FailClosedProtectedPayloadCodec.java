package com.xuntian.mock.control.security;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedProtectedPayloadCodec implements ProtectedPayloadCodec {

    @Override
    public String protect(byte[] plaintext) {
        throw unavailable();
    }

    @Override
    public byte[] unprotect(String protectedPayload) {
        throw unavailable();
    }

    private PlatformException unavailable() {
        return new PlatformException(ErrorCode.INTERNAL_ERROR, "Production KMS adapter is not configured");
    }
}
