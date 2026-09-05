package com.xuntian.mock.control.callback;

import java.time.Instant;

public record CallbackAdminOperationRecord(
        long id,
        String requestId,
        String operationType,
        String resourceType,
        String resourceId,
        String requestChecksum,
        String status,
        String resultJson,
        String operator,
        Instant createdAt,
        Instant completedAt) {
}
