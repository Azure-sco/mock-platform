package com.xuntian.mock.runtime.engine;

import java.util.Objects;

public record ApiKey(String provider, String api) {
    public ApiKey {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(api, "api");
        if (!safe(provider) || !safe(api)) {
            throw new IllegalArgumentException("Provider and API codes must be safe identifiers");
        }
    }

    private static boolean safe(String value) {
        return !value.isBlank() && value.length() <= 128
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }
}
