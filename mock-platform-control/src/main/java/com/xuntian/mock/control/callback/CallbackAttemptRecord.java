package com.xuntian.mock.control.callback;

import java.time.Instant;

public record CallbackAttemptRecord(
        long id,
        String taskId,
        String deliveryId,
        int attemptNo,
        Integer sendAttemptNo,
        long fencingToken,
        String status,
        Instant startedAt,
        Instant completedAt,
        Integer httpStatus,
        String result,
        String deliveryCertainty,
        String errorMasked,
        Long durationMs) {
}
