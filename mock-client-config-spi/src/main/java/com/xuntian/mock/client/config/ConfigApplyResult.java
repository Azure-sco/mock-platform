package com.xuntian.mock.client.config;

public final class ConfigApplyResult {

    public enum Status {
        APPLIED,
        REJECTED
    }

    private final Status status;
    private final String activationId;
    private final long configVersion;
    private final String errorCode;

    private ConfigApplyResult(Status status, String activationId, long configVersion, String errorCode) {
        this.status = status;
        this.activationId = activationId;
        this.configVersion = configVersion;
        this.errorCode = errorCode;
    }

    public static ConfigApplyResult applied(String activationId, long configVersion) {
        return new ConfigApplyResult(Status.APPLIED, activationId, configVersion, null);
    }

    public static ConfigApplyResult rejected(String activationId, long configVersion, String errorCode) {
        return new ConfigApplyResult(Status.REJECTED, activationId, configVersion, errorCode);
    }

    public Status status() {
        return status;
    }

    public String activationId() {
        return activationId;
    }

    public long configVersion() {
        return configVersion;
    }

    public String errorCode() {
        return errorCode;
    }
}
