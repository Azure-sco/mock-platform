package com.xuntian.mock.control.flow;

import java.time.Instant;

public record FlowDefinitionVersionRecord(
        long id,
        long flowDefinitionId,
        int versionNo,
        String status,
        String initialState,
        long ttlSeconds,
        String participantApisJson,
        String variablesJson,
        String transitionsJson,
        String compiledJson,
        String checksum,
        String validationStatus,
        String validationResultJson,
        Long approvalRequestId,
        Instant approvedAt,
        Instant publishedAt,
        Instant deprecatedAt,
        String createdBy,
        Instant createdAt) {
}
