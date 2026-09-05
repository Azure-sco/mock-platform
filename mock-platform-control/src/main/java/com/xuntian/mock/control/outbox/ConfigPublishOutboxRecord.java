package com.xuntian.mock.control.outbox;

import java.time.Instant;

public record ConfigPublishOutboxRecord(
        long id,
        String aggregateType,
        String aggregateId,
        String targetType,
        String targetNamespace,
        String payloadEncrypted,
        String checksum,
        String status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastErrorMasked,
        String leaseOwner,
        Instant leaseUntil,
        long fencingToken,
        Instant createdAt,
        Instant publishedAt) {
}
