package com.xuntian.mock.control.flow;

import java.time.Instant;

public record FlowDefinitionRecord(
        long id,
        long providerId,
        String flowCode,
        String flowName,
        Integer currentDraftVersion,
        String status,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {
}
