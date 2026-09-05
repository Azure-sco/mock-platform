package com.xuntian.mock.control.release;

import java.time.Instant;

public record ReleaseActivationRecord(
        String id,
        String environment,
        String appCode,
        String fromReleaseId,
        String toReleaseId,
        long fromActivationVersion,
        long toActivationVersion,
        String action,
        String status,
        String requestId,
        String operator,
        Instant deadlineAt,
        Instant createdAt,
        Instant completedAt) {
}
