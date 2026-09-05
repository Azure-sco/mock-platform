package com.xuntian.mock.client.core.failure;

/**
 * Raised before a MOCK/CANARY request is dispatched when a newer configuration was observed but
 * could not be authenticated and compiled.
 */
public final class MockConfigInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    public static final String ERROR_CODE = "MOCK_CONFIG_INVALID";

    private final long activeConfigVersion;
    private final long observedConfigVersion;

    public MockConfigInvalidException(long activeConfigVersion, long observedConfigVersion) {
        super(ERROR_CODE + ": a newer mock configuration was rejected");
        this.activeConfigVersion = activeConfigVersion;
        this.observedConfigVersion = observedConfigVersion;
    }

    public String errorCode() {
        return ERROR_CODE;
    }

    public long activeConfigVersion() {
        return activeConfigVersion;
    }

    public long observedConfigVersion() {
        return observedConfigVersion;
    }
}
