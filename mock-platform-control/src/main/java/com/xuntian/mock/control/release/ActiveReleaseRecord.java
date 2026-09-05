package com.xuntian.mock.control.release;

import java.time.Instant;

public record ActiveReleaseRecord(
        String environment,
        String appCode,
        String releaseId,
        long activationVersion,
        String state,
        Instant updatedAt) {
}
