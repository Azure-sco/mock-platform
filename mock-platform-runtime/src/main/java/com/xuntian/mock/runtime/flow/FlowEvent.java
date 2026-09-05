package com.xuntian.mock.runtime.flow;

import java.time.Instant;

public record FlowEvent(
        long id,
        String eventId,
        long flowInstanceId,
        int flowGeneration,
        SourceType sourceType,
        String mockRequestId,
        Integer requestExecutionGeneration,
        String internalExecutionId,
        String sourceApiCode,
        String transitionId,
        String fromState,
        String toState,
        String eventType,
        long queryCount,
        String operator,
        Instant eventAt) {

    public FlowEvent persisted(long persistedId) {
        return new FlowEvent(
                persistedId, eventId, flowInstanceId, flowGeneration, sourceType, mockRequestId,
                requestExecutionGeneration, internalExecutionId, sourceApiCode, transitionId,
                fromState, toState, eventType, queryCount, operator, eventAt);
    }

    public enum SourceType { SDK, TIMER, MANUAL }
}
