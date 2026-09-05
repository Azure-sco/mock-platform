package com.xuntian.mock.control.scenario;

import java.time.Instant;

public record ScenarioVersionRecord(
        long id,
        long scenarioId,
        int versionNo,
        String status,
        long contractVersionId,
        Long flowDefinitionVersionId,
        int priority,
        Instant effectiveFrom,
        Instant effectiveTo,
        String scopeJson,
        String matchRuleJson,
        String responseJson,
        String callbackJson,
        String compiledJson,
        String checksum,
        String validationStatus,
        String validationResultJson,
        Long approvalRequestId,
        Instant approvedAt,
        Instant publishedAt,
        Instant disabledAt,
        String createdBy,
        Instant createdAt) {
}
