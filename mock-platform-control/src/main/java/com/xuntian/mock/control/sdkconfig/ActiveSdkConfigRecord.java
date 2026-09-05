package com.xuntian.mock.control.sdkconfig;

import java.time.Instant;

public record ActiveSdkConfigRecord(
        String appCode,
        String environment,
        long desiredEnvelopeId,
        long desiredConfigVersion,
        Long lastAppliedEnvelopeId,
        Long lastAppliedConfigVersion,
        String activationId,
        String state,
        Instant updatedAt) {
}
