package com.xuntian.mock.runtime.requestlog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.RuntimeProperties;
import com.xuntian.mock.runtime.engine.RuntimeExecution;
import com.xuntian.mock.runtime.engine.RuntimeRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

public record RequestLogEntry(
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

    private static final ObjectMapper SUMMARY_MAPPER = new ObjectMapper();

    public static RequestLogEntry success(
            RuntimeRequest request,
            RuntimeExecution execution,
            long durationMs,
            RuntimeProperties properties,
            Instant createdAt) {
        return create(
                request,
                execution.scenarioId(),
                execution.scenarioVersionId(),
                execution.releaseId(),
                execution.status(),
                "MATCHED",
                null,
                execution.body().length,
                durationMs,
                properties,
                createdAt);
    }

    public static RequestLogEntry failure(
            RuntimeRequest request,
            Throwable failure,
            long durationMs,
            RuntimeProperties properties,
            Instant createdAt) {
        String code = failure instanceof PlatformException platform
                ? platform.errorCode().name()
                : ErrorCode.MOCK_INTERNAL_ERROR.name();
        int status = failure instanceof PlatformException platform
                ? platform.errorCode().httpStatus()
                : ErrorCode.MOCK_INTERNAL_ERROR.httpStatus();
        return create(
                request, null, null, null, status, "FAILED", code, 0,
                durationMs, properties, createdAt);
    }

    private static RequestLogEntry create(
            RuntimeRequest request,
            String scenarioId,
            String scenarioVersionId,
            String releaseId,
            Integer status,
            String result,
            String errorCode,
            int responseBytes,
            long durationMs,
            RuntimeProperties properties,
            Instant createdAt) {
        Map<String, Object> requestFields = new LinkedHashMap<>();
        requestFields.put("bodyBytes", request.bodyLength());
        requestFields.put("contentType", safe(request.contentType(), 128));
        requestFields.put("headerCount", request.headers().size());
        requestFields.put("queryNameCount", request.query().size());
        String requestSummary = json(requestFields);
        String responseSummary = json(Map.of("bodyBytes", responseBytes));
        return new RequestLogEntry(
                UUID.randomUUID().toString(),
                safe(request.mockRequestId(), 64),
                safe(request.traceId(), 64),
                safe(request.environment(), 32),
                safe(request.app(), 128),
                nullable(request.tenant(), 128),
                mask(request.testAccount()),
                safe(request.provider(), 64),
                safe(request.api(), 64),
                nullable(scenarioId, 64),
                nullable(scenarioVersionId, 64),
                nullable(releaseId, 64),
                safe(request.method(), 16),
                safe(request.rawPath(), 1024),
                requestSummary,
                responseSummary,
                status,
                result,
                Math.max(0, durationMs),
                errorCode,
                createdAt.plus(properties.getRequestLogRetentionDays(), ChronoUnit.DAYS),
                createdAt);
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 2 ? "**" : value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }

    private static String safe(String value, int max) {
        String candidate = value == null ? "" : value.replace('\r', '_').replace('\n', '_');
        return candidate.length() <= max ? candidate : candidate.substring(0, max);
    }

    private static String nullable(String value, int max) {
        return value == null ? null : safe(value, max);
    }

    private static String json(Map<String, Object> values) {
        try {
            return SUMMARY_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize safe Request Log summary", failure);
        }
    }
}
