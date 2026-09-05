package com.xuntian.mock.control.security;

public interface ProtectedPayloadCodec {

    String protect(byte[] plaintext);

    byte[] unprotect(String protectedPayload);
}
