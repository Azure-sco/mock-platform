package com.xuntian.mock.control.securitypolicy;

import java.time.Instant;

public record RuntimePolicyAckRecord(
        long id,
        String runtimeNodeId,
        String bindingId,
        String environment,
        String appCode,
        String policyType,
        long policyVersionId,
        long bindingVersion,
        String status,
        String errorMasked,
        Instant reportedAt) {
}
