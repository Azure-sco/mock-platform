package com.xuntian.mock.client.config;

import java.util.Objects;

/** Exact policy identity echoed in APPLIED ACKs for Control-side reconciliation. */
public final class SdkSecurityPolicyRef {

    private final long policyVersionId;
    private final String policyType;
    private final String scopeKey;
    private final String checksum;

    public SdkSecurityPolicyRef(
            long policyVersionId,
            String policyType,
            String scopeKey,
            String checksum) {
        if (policyVersionId <= 0L) {
            throw new IllegalArgumentException("policyVersionId must be positive");
        }
        this.policyVersionId = policyVersionId;
        this.policyType = requireSafe(policyType, 64, "policyType");
        this.scopeKey = requireSafe(scopeKey, 270, "scopeKey");
        this.checksum = Objects.requireNonNull(checksum, "checksum");
        if (!checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksum is invalid");
        }
    }

    public long policyVersionId() {
        return policyVersionId;
    }

    public String policyType() {
        return policyType;
    }

    public String scopeKey() {
        return scopeKey;
    }

    public String checksum() {
        return checksum;
    }

    private static String requireSafe(String value, int maximumLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
        return value;
    }
}
