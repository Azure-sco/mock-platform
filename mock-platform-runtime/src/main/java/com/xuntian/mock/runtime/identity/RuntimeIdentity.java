package com.xuntian.mock.runtime.identity;

public record RuntimeIdentity(
        String appCode,
        String environment,
        String tenantCode,
        String testAccount) {

    public RuntimeIdentity(String appCode, String environment) {
        this(appCode, environment, null, null);
    }
}
