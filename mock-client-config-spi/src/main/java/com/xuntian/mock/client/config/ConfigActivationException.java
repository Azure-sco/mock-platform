package com.xuntian.mock.client.config;

final class ConfigActivationException extends Exception {

    private static final long serialVersionUID = 1L;
    private final String errorCode;
    private final ActivationMetadata metadata;

    ConfigActivationException(String errorCode, ActivationMetadata metadata) {
        super(errorCode);
        this.errorCode = errorCode;
        this.metadata = metadata;
    }

    String errorCode() {
        return errorCode;
    }

    ActivationMetadata metadata() {
        return metadata;
    }
}
