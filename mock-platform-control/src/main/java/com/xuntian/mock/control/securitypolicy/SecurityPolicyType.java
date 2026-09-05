package com.xuntian.mock.control.securitypolicy;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;

import java.util.Locale;

public enum SecurityPolicyType {
    APP_ACL("LIVE_ADMISSION"),
    PROVIDER_ENVIRONMENT("RELEASE"),
    SDK_HEADER_FILTER("SDK_CONFIG"),
    CALLBACK_ALLOWLIST("RELEASE"),
    CALLBACK_SIGNATURE("RELEASE"),
    SDK_FALLBACK_REAL("SDK_CONFIG");

    private final String effectMode;

    SecurityPolicyType(String effectMode) {
        this.effectMode = effectMode;
    }

    public String effectMode() {
        return effectMode;
    }

    public static SecurityPolicyType parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "policyType is invalid", failure);
        }
    }
}
