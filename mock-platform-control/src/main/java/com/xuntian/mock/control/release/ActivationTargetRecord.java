package com.xuntian.mock.control.release;

import java.time.Instant;

public record ActivationTargetRecord(
        long id,
        String activationId,
        String runtimeNodeId,
        boolean required,
        String status,
        Instant capturedAt,
        Instant updatedAt,
        String waivedBy,
        String waiveReason) {
}
