package com.xuntian.mock.control.contract;

import java.time.Instant;

public record ContractVersionRecord(
        long id,
        long apiId,
        int versionNo,
        String status,
        String requestSchemaJson,
        String responseSchemaJson,
        String examplesJson,
        String errorCodesJson,
        String businessKeyExtractorJson,
        String signatureMetadataJson,
        String sourceType,
        String sourceFileHash,
        String checksum,
        String createdBy,
        Instant createdAt,
        String publishedBy,
        Instant publishedAt) {
}
