package com.xuntian.mock.control.release;

import java.time.Instant;

public record ReleaseRecord(
        String id,
        String releaseCode,
        String environment,
        String appCode,
        String status,
        String snapshotJson,
        byte[] snapshotBytes,
        String checksum,
        String schemaVersion,
        byte[] signature,
        String signatureKeyId,
        String signatureAlgorithm,
        String releaseNote,
        String failureReason,
        String createdBy,
        Instant createdAt,
        String publishedBy,
        Instant publishedAt) {
}
