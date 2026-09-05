package com.xuntian.mock.control.flow;

import java.time.Instant;

public record FlowEventRecord(
        long id,
        String eventId,
        long flowInstanceId,
        int flowGeneration,
        String sourceType,
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
        Instant eventAt) { }
