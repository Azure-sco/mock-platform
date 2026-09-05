package com.xuntian.mock.client.config;

import java.security.PublicKey;

/** Supplies trusted SDK configuration signing keys by immutable key identifier. */
public interface ConfigSignatureKeyProvider {

    PublicKey findTrustedKey(String keyId);
}
