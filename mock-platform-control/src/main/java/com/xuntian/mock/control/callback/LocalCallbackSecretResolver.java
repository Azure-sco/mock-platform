package com.xuntian.mock.control.callback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

@Component
@Profile({"local", "test"})
public final class LocalCallbackSecretResolver implements CallbackSecretResolver {

    private final Map<String, String> secrets;

    public LocalCallbackSecretResolver(
            @Value("${MOCK_CALLBACK_SECRETS_JSON:{}}") String source,
            ObjectMapper mapper) {
        try { this.secrets = Map.copyOf(mapper.readValue(source, new TypeReference<Map<String, String>>() { })); }
        catch (Exception failure) { throw new IllegalArgumentException("MOCK_CALLBACK_SECRETS_JSON is invalid", failure); }
    }

    @Override
    public byte[] resolve(String secretRef) {
        String encoded = secrets.get(secretRef);
        if (encoded == null) throw new CallbackDispatcher.PreparationException("Callback secretRef is unavailable");
        try { return Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException failure) {
            throw new CallbackDispatcher.PreparationException("Callback secretRef value is invalid", failure);
        }
    }
}
