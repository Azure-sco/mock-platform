package com.xuntian.mock.control.flow;

import java.time.Instant;

public record FlowInstanceRecord(
        long id,
        String flowKey,
        String environment,
        String appCode,
        String providerCode,
        String flowCode,
        String tenantCode,
        String testAccount,
        String businessNoHmac,
        String hmacKeyVersion,
        String businessNoMasked,
        String releaseId,
        long flowDefinitionVersionId,
        String flowDefinitionChecksum,
        int generation,
        String status,
        String currentState,
        long queryCount,
        String variablesJson,
        long version,
        String pendingTransitionId,
        Instant nextTransitionAt,
        Instant expireAt,
        Instant createdAt,
        Instant updatedAt) { }
