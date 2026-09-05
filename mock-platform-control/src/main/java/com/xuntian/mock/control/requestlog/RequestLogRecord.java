package com.xuntian.mock.control.requestlog;

import java.time.Instant;

public record RequestLogRecord(
        String id,
        String mockRequestId,
        String traceId,
        String environment,
        String appCode,
        String tenantCode,
        String testAccountMasked,
        String providerCode,
        String apiCode,
        String scenarioId,
        String scenarioVersionId,
        String releaseId,
        String flowKey,
        String businessNoHmac,
        String hmacKeyVersion,
        String httpMethod,
        String path,
        String requestSummary,
        String responseSummary,
        Integer httpStatus,
        String matchResult,
        long durationMs,
        String errorCode,
        Instant expireAt,
        Instant createdAt) {
}
