package com.xuntian.mock.control.sdkconfig;

import java.time.Instant;

public record SdkConfigActivationRecord(
        String id,
        String appCode,
        String environment,
        long sdkConfigEnvelopeId,
        Long fromConfigVersion,
        long toConfigVersion,
        String status,
        String requestId,
        String operator,
        Instant createdAt,
        Instant completedAt) {
}
