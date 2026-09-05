package com.xuntian.mock.client.config;

final class ActivationMetadata {

    static final ActivationMetadata EMPTY = new ActivationMetadata(null, null, null, null, -1L);

    private final String activationId;
    private final String appCode;
    private final String environment;
    private final String envelopeId;
    private final long configVersion;

    ActivationMetadata(
            String activationId,
            String appCode,
            String environment,
            String envelopeId,
            long configVersion) {
        this.activationId = activationId;
        this.appCode = appCode;
        this.environment = environment;
        this.envelopeId = envelopeId;
        this.configVersion = configVersion;
    }

    String activationId() {
        return activationId;
    }

    String appCode() {
        return appCode;
    }

    String environment() {
        return environment;
    }

    String envelopeId() {
        return envelopeId;
    }

    long configVersion() {
        return configVersion;
    }
}
