package com.xuntian.mock.runtime.release;

public record ReleaseScope(String environment, String app) {

    public ReleaseScope {
        requireSafe(environment, 32, "environment");
        requireSafe(app, 128, "app");
    }

    private static void requireSafe(String value, int maxLength, String name) {
        if (value == null || value.length() > maxLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
