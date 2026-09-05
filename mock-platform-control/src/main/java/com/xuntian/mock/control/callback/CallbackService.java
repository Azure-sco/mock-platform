package com.xuntian.mock.control.callback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PageResult;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CallbackService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of(
            "NEW", "RETRYING", "RUNNING", "SUCCESS", "FAILED",
            "FAILED_PREPARATION", "FAILED_UNCONFIRMED", "CANCELLED");
    private final CallbackMapper mapper;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CallbackService(
            CallbackMapper mapper,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mapper = mapper;
        this.audit = audit;
        this.objectMapper = objectMapper.copy();
        this.clock = clock;
    }

    public PageResult<CallbackTaskRecord> find(CallbackTaskFilter filter, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("page must be >= 0 and size must be between 1 and 100");
        }
        if (filter.status() != null && !STATUSES.contains(filter.status())) {
            throw invalid("Callback status is invalid");
        }
        if (filter.createdFrom() != null && filter.createdTo() != null
                && !filter.createdFrom().isBefore(filter.createdTo())) {
            throw invalid("createdFrom must be before createdTo");
        }
        long total = mapper.count(filter);
        return new PageResult<>(mapper.selectPage(filter, size, (long) page * size), total, page, size);
    }

    public CallbackDetail detail(String taskId) {
        CallbackTaskRecord task = require(taskId);
        return new CallbackDetail(task, mapper.selectAttempts(task.taskId()));
    }

    @Transactional
    public OperationResult retry(
            String taskId,
            String operationRequestId,
            long delayMs,
            OperatorContext operator) {
        if (delayMs < 0 || delayMs > 86_400_000L) throw invalid("delayMs must be from 0 to 86400000");
        return operate("CALLBACK_RETRY", taskId, operationRequestId,
                "Manual retry after " + delayMs + "ms", operator, task -> {
            if ("RUNNING".equals(task.status())) {
                throw new PlatformException(ErrorCode.MOCK_FLOW_OPERATION_BUSY, "Callback Task is currently RUNNING");
            }
            if (!Set.of("FAILED", "FAILED_PREPARATION", "FAILED_UNCONFIRMED").contains(task.status())) {
                throw new PlatformException(ErrorCode.INVALID_STATE, "Only a failed Callback Task can be retried");
            }
            int sendGrant = "FAILED_PREPARATION".equals(task.status()) ? 0 : 1;
            int preparationGrant = "FAILED_PREPARATION".equals(task.status()) ? 1 : 0;
            if (mapper.manualRetry(task.taskId(), task.status(), clock.instant().plusMillis(delayMs),
                    sendGrant, preparationGrant) != 1) {
                throw new PlatformException(ErrorCode.CONFLICT, "Callback Task status changed concurrently");
            }
            return new OperationResult(task.taskId(), "RETRYING", operationRequestId);
        });
    }

    @Transactional
    public OperationResult cancel(String taskId, String operationRequestId, OperatorContext operator) {
        return operate("CALLBACK_CANCEL", taskId, operationRequestId, "Manual cancel", operator, task -> {
            if ("RUNNING".equals(task.status())) {
                throw new PlatformException(ErrorCode.MOCK_FLOW_OPERATION_BUSY, "Callback Task is currently RUNNING");
            }
            if ("CANCELLED".equals(task.status())) {
                return new OperationResult(task.taskId(), "CANCELLED", operationRequestId);
            }
            if (!Set.of("NEW", "RETRYING").contains(task.status())) {
                throw new PlatformException(ErrorCode.INVALID_STATE, "Only a pending Callback Task can be cancelled");
            }
            if (mapper.cancelPending(task.taskId(), "Manual cancel") != 1) {
                throw new PlatformException(ErrorCode.CONFLICT, "Callback Task status changed concurrently");
            }
            return new OperationResult(task.taskId(), "CANCELLED", operationRequestId);
        });
    }

    private OperationResult operate(
            String operation,
            String rawTaskId,
            String rawOperationRequestId,
            String rawReason,
            OperatorContext operator,
            Mutation mutation) {
        String taskId = required(rawTaskId, "taskId", 64);
        String operationRequestId = required(rawOperationRequestId, "requestId", 64);
        String reason = required(rawReason, "reason", 512);
        String checksum = Checksum.sha256Hex(CanonicalJson.write(Map.of(
                "operation", operation, "taskId", taskId, "reason", reason)));
        mapper.insertAdminOperation(
                operationRequestId, operation, taskId, checksum, operator.operatorId());
        CallbackAdminOperationRecord operationRecord = mapper.lockAdminOperation(operationRequestId);
        if (operationRecord == null
                || !operation.equals(operationRecord.operationType())
                || !taskId.equals(operationRecord.resourceId())
                || !checksum.equals(operationRecord.requestChecksum())) {
            throw new PlatformException(
                    ErrorCode.MOCK_IDEMPOTENCY_CONFLICT,
                    "Request ID was already used for a different management operation");
        }
        if ("COMPLETED".equals(operationRecord.status())) {
            return result(operationRecord.resultJson());
        }
        CallbackTaskRecord before = mapper.lockTask(taskId);
        if (before == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Callback Task not found");
        OperationResult result = mutation.apply(before);
        mapper.completeAdminOperation(operationRequestId, json(result), clock.instant());
        OperatorContext auditOperator = new OperatorContext(
                operator.operatorId(), operator.roles(), operationRequestId);
        audit.record(auditOperator, operation, "CALLBACK_TASK", taskId, checksum,
                Map.of("status", before.status()), Map.of("status", result.status(), "reason", reason));
        return result;
    }

    private CallbackTaskRecord require(String rawTaskId) {
        String taskId = required(rawTaskId, "taskId", 64);
        CallbackTaskRecord task = mapper.selectTask(taskId);
        if (task == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Callback Task not found");
        return task;
    }

    private OperationResult result(String json) {
        try { return objectMapper.readValue(json, OperationResult.class); }
        catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Callback operation result is invalid", failure);
        }
    }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Callback operation result cannot be serialized", failure);
        }
    }
    static String optional(String value, String field, int maxLength) {
        return value == null || value.isBlank() ? null : required(value, field, maxLength);
    }
    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) throw invalid(field + " is invalid");
        return value.trim();
    }
    private static PlatformException invalid(String message) {
        return new PlatformException(ErrorCode.INVALID_REQUEST, message);
    }

    private interface Mutation { OperationResult apply(CallbackTaskRecord task); }
    public record CallbackDetail(CallbackTaskRecord task, List<CallbackAttemptRecord> attempts) { }
    public record OperationResult(String taskId, String status, String requestId) { }
}
