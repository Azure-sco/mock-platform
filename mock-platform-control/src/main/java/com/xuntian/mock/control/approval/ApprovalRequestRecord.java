package com.xuntian.mock.control.approval;

import java.time.Instant;

public record ApprovalRequestRecord(
        long id,
        String objectType,
        long objectId,
        String objectChecksum,
        String policyCode,
        int requiredCount,
        String status,
        String requestedBy,
        Instant requestedAt,
        Instant completedAt) {
}
