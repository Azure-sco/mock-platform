package com.xuntian.mock.control.scenario;

import java.time.Instant;

public record ScenarioRecord(
        long id,
        String scenarioCode,
        String scenarioName,
        long providerId,
        long apiId,
        Integer currentDraftVersion,
        String status,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {
}
