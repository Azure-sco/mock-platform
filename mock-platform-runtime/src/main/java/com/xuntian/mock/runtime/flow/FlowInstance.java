package com.xuntian.mock.runtime.flow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record FlowInstance(
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
        String flowDefinitionVersionId,
        String flowDefinitionChecksum,
        int generation,
        Status status,
        String currentState,
        long queryCount,
        Map<String, Object> variables,
        long version,
        String pendingTransitionId,
        Instant nextTransitionAt,
        Instant expireAt,
        Instant createdAt,
        Instant updatedAt) {

    public FlowInstance {
        Objects.requireNonNull(flowKey, "flowKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expireAt, "expireAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (generation < 1 || queryCount < 0 || version < 0) {
            throw new IllegalArgumentException("Flow counters are invalid");
        }
        variables = Map.copyOf(new LinkedHashMap<>(variables == null ? Map.of() : variables));
        if ((pendingTransitionId == null) != (nextTransitionAt == null)) {
            throw new IllegalArgumentException("Pending Flow transition fields must be set together");
        }
    }

    public FlowInstance persisted(long persistedId) {
        return copy(persistedId, releaseId, flowDefinitionVersionId, flowDefinitionChecksum,
                generation, status, currentState, queryCount, variables, version,
                pendingTransitionId, nextTransitionAt, expireAt, updatedAt);
    }

    public FlowInstance progressed(
            String state,
            long newQueryCount,
            Map<String, Object> newVariables,
            String pendingId,
            Instant pendingAt,
            Instant now) {
        return copy(id, releaseId, flowDefinitionVersionId, flowDefinitionChecksum,
                generation, status, state, newQueryCount, newVariables, version + 1,
                pendingId, pendingAt, expireAt, now);
    }

    public FlowInstance expired(Instant now) {
        return copy(id, releaseId, flowDefinitionVersionId, flowDefinitionChecksum,
                generation, Status.EXPIRED, currentState, queryCount, variables, version + 1,
                null, null, expireAt, now);
    }

    public FlowInstance deleted(Instant now) {
        return copy(id, releaseId, flowDefinitionVersionId, flowDefinitionChecksum,
                generation + 1, Status.DELETED, currentState, queryCount, variables, version + 1,
                null, null, expireAt, now);
    }

    public FlowInstance reset(
            CompiledFlowDefinition definition,
            String newReleaseId,
            Map<String, Object> initialVariables,
            Instant now) {
        CompiledFlowDefinition.PendingTransition pending = definition.pendingTransition(definition.initialState(), now);
        return copy(id, newReleaseId, definition.flowDefinitionVersionId(), definition.checksum(),
                generation + 1, Status.ACTIVE, definition.initialState(), 0, initialVariables, version + 1,
                pending.transitionId(), pending.executeAt(), now.plus(definition.ttl()), now);
    }

    public FlowInstance reactivate(
            CompiledFlowDefinition definition,
            String newReleaseId,
            String newFlowKey,
            String newBusinessHmac,
            String newHmacVersion,
            String newBusinessMasked,
            Map<String, Object> initialVariables,
            Instant now) {
        CompiledFlowDefinition.PendingTransition pending = definition.pendingTransition(definition.initialState(), now);
        return new FlowInstance(
                id, newFlowKey, environment, appCode, providerCode, definition.flowCode(), tenantCode,
                testAccount, newBusinessHmac, newHmacVersion, newBusinessMasked, newReleaseId,
                definition.flowDefinitionVersionId(), definition.checksum(), generation + 1,
                Status.ACTIVE, definition.initialState(), 0, initialVariables, version + 1,
                pending.transitionId(), pending.executeAt(), now.plus(definition.ttl()), createdAt, now);
    }

    private FlowInstance copy(
            long newId,
            String newReleaseId,
            String newDefinitionVersionId,
            String newDefinitionChecksum,
            int newGeneration,
            Status newStatus,
            String newState,
            long newQueryCount,
            Map<String, Object> newVariables,
            long newVersion,
            String newPendingId,
            Instant newPendingAt,
            Instant newExpireAt,
            Instant newUpdatedAt) {
        return new FlowInstance(
                newId, flowKey, environment, appCode, providerCode, flowCode, tenantCode, testAccount,
                businessNoHmac, hmacKeyVersion, businessNoMasked, newReleaseId,
                newDefinitionVersionId, newDefinitionChecksum, newGeneration, newStatus, newState,
                newQueryCount, newVariables, newVersion, newPendingId, newPendingAt, newExpireAt,
                createdAt, newUpdatedAt);
    }

    public enum Status { ACTIVE, EXPIRED, DELETED }
}
