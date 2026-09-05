package com.xuntian.mock.control.security;

public interface PayloadSigner {

    SignatureValue sign(byte[] canonicalPayload);

    record SignatureValue(String signature, String keyId, String algorithm) {
    }
}
