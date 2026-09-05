package com.xuntian.mock.control.api;

import java.time.Instant;

public record ApiRecord(
        long id,
        long providerId,
        String apiCode,
        String apiName,
        String httpMethod,
        String path,
        String contentType,
        String owner,
        String status,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {
}
