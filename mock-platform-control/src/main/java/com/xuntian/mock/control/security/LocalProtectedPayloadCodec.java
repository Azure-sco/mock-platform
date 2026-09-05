package com.xuntian.mock.control.security;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
@Profile({"local", "test"})
public final class LocalProtectedPayloadCodec implements ProtectedPayloadCodec {

    private static final String PREFIX = "local-b64-v1:";

    @Override
    public String protect(byte[] plaintext) {
        return PREFIX + Base64.getEncoder().encodeToString(plaintext);
    }

    @Override
    public byte[] unprotect(String protectedPayload) {
        if (protectedPayload == null || !protectedPayload.startsWith(PREFIX)) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Protected payload format is invalid");
        }
        try {
            return Base64.getDecoder().decode(protectedPayload.substring(PREFIX.length()));
        } catch (IllegalArgumentException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Protected payload cannot be decoded", failure);
        }
    }
}
