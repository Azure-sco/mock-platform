package com.xuntian.mock.control.provider;

import java.time.Instant;

public record ProviderRecord(
        long id,
        String providerCode,
        String providerName,
        String owner,
        String status,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {
}
