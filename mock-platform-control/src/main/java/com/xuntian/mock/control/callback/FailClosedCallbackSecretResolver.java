package com.xuntian.mock.control.callback;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedCallbackSecretResolver implements CallbackSecretResolver {
    @Override
    public byte[] resolve(String secretRef) {
        throw new CallbackDispatcher.PreparationException("Production Callback KMS adapter is not configured");
    }
}
