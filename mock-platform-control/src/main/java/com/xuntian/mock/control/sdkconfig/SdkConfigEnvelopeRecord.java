package com.xuntian.mock.control.sdkconfig;

import java.time.Instant;

public record SdkConfigEnvelopeRecord(
        long id,
        String appCode,
        String environment,
        long configVersion,
        String routingJson,
        String securityPolicyRefsJson,
        String securityPolicyPayloadsEncrypted,
        Instant effectiveAt,
        Instant expireAt,
        String checksum,
        String signature,
        String signatureKeyId,
        String signatureAlgorithm,
        String validationStatus,
        String status,
        Long approvalRequestId,
        String sourceAuditRef,
        String createdBy,
        Instant createdAt,
        String publishedBy,
        Instant publishedAt) {
}
