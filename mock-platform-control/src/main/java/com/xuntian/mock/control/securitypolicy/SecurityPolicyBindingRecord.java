package com.xuntian.mock.control.securitypolicy;

import java.time.Instant;

public record SecurityPolicyBindingRecord(
        String id,
        String policyType,
        String scopeKey,
        long desiredPolicyVersionId,
        Long effectivePolicyVersionId,
        String effectMode,
        String status,
        long bindingVersion,
        Instant desiredAt,
        Instant boundAt,
        String firstEffectiveReleaseId,
        String currentEffectiveReleaseId,
        Long effectiveActivationVersion,
        Long sdkEffectiveConfigVersion,
        Instant effectiveAt,
        String updatedBy,
        Instant updatedAt) {
}
