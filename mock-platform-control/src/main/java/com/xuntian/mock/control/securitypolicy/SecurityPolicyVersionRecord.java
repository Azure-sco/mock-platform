package com.xuntian.mock.control.securitypolicy;

import java.time.Instant;

public record SecurityPolicyVersionRecord(
        long id,
        String policyId,
        String policyType,
        String scopeKey,
        int versionNo,
        String configJsonEncrypted,
        String checksum,
        String status,
        String signature,
        String signatureKeyId,
        String signatureAlgorithm,
        String sourceAuditRef,
        Long approvalRequestId,
        String createdBy,
        Instant createdAt,
        String publishedBy,
        Instant publishedAt) {
}
