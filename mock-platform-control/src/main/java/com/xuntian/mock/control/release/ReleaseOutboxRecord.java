package com.xuntian.mock.control.release;

import java.time.Instant;

public record ReleaseOutboxRecord(
        long id,
        String activationId,
        String aggregateKey,
        long activationVersion,
        String payloadJson,
        byte[] payloadBytes,
        String status,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        long fencingToken,
        String lastErrorMasked,
        Instant createdAt,
        Instant updatedAt,
        Instant projectedAt) {
}
