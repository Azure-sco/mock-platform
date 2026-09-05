package com.xuntian.mock.client.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SdkConfigAck {

    public enum Status {
        APPLIED,
        REJECTED
    }

    private final String activationId;
    private final String appCode;
    private final String environment;
    private final String sdkInstanceId;
    private final long envelopeId;
    private final long oldConfigVersion;
    private final long newConfigVersion;
    private final List<SdkSecurityPolicyRef> securityPolicyRefs;
    private final Status status;
    private final Instant effectiveAt;
    private final String errorMasked;

    public SdkConfigAck(
            String activationId,
            String appCode,
            String environment,
            String sdkInstanceId,
            long envelopeId,
            long oldConfigVersion,
            long newConfigVersion,
            List<SdkSecurityPolicyRef> securityPolicyRefs,
            Status status,
            Instant effectiveAt,
            String errorMasked) {
        this.activationId = Objects.requireNonNull(activationId, "activationId");
        this.appCode = Objects.requireNonNull(appCode, "appCode");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.sdkInstanceId = Objects.requireNonNull(sdkInstanceId, "sdkInstanceId");
        this.envelopeId = envelopeId;
        this.oldConfigVersion = oldConfigVersion;
        this.newConfigVersion = newConfigVersion;
        this.securityPolicyRefs = Collections.unmodifiableList(
                new ArrayList<SdkSecurityPolicyRef>(securityPolicyRefs));
        this.status = Objects.requireNonNull(status, "status");
        this.effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
        this.errorMasked = errorMasked;
        requireSafe(activationId, 64, "activationId");
        requireSafe(appCode, 128, "appCode");
        requireSafe(environment, 32, "environment");
        requireSafe(sdkInstanceId, 128, "sdkInstanceId");
        if (envelopeId <= 0L) {
            throw new IllegalArgumentException("envelopeId must be positive");
        }
        if (securityPolicyRefs.size() > 5_000) {
            throw new IllegalArgumentException("securityPolicyRefs is too large");
        }
        for (SdkSecurityPolicyRef policyRef : securityPolicyRefs) {
            Objects.requireNonNull(policyRef, "securityPolicyRef");
        }
        if (errorMasked != null) {
            requireSafe(errorMasked, 128, "errorMasked");
        }
        if ((status == Status.APPLIED && errorMasked != null)
                || (status == Status.REJECTED && errorMasked == null)) {
            throw new IllegalArgumentException("errorMasked does not match acknowledgement status");
        }
    }

    public String activationId() {
        return activationId;
    }

    public String appCode() {
        return appCode;
    }

    public String environment() {
        return environment;
    }

    public String sdkInstanceId() {
        return sdkInstanceId;
    }

    public long envelopeId() {
        return envelopeId;
    }

    public long oldConfigVersion() {
        return oldConfigVersion;
    }

    public long newConfigVersion() {
        return newConfigVersion;
    }

    public List<SdkSecurityPolicyRef> securityPolicyRefs() {
        return securityPolicyRefs;
    }

    public Status status() {
        return status;
    }

    public Instant effectiveAt() {
        return effectiveAt;
    }

    public String errorMasked() {
        return errorMasked;
    }

    /** Control uses this stable pair as its unique idempotency key. */
    public String idempotencyKey() {
        return activationId + '\u0000' + sdkInstanceId;
    }

    private static void requireSafe(String value, int maximumLength, String name) {
        if (value == null || value.isEmpty() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            boolean safe = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.'
                    || character == '_'
                    || character == '-'
                    || character == ':';
            if (!safe) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
    }
}
