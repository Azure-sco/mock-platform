package com.xuntian.mock.control.sdkconfig;

import java.time.Instant;

public record SdkConfigTargetRecord(
        long id,
        String activationId,
        String sdkInstanceId,
        boolean required,
        String status,
        Instant capturedAt,
        Instant updatedAt,
        String waivedBy,
        String waiveReason) {
}
