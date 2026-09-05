package com.xuntian.mock.control.callback;

public interface CallbackSecretResolver {
    byte[] resolve(String secretRef);
}
