package com.xuntian.mock.control.approval;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ApprovalService {

    private final ApprovalMapper mapper;
    private final AuditService auditService;
    private final Clock clock;
    private final Map<String, ApprovalSubjectHandler> handlers;

    public ApprovalService(
            ApprovalMapper mapper,
            AuditService auditService,
            Clock clock,
            List<ApprovalSubjectHandler> handlers) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.clock = clock;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                handler -> normalizeType(handler.objectType()), Function.identity()));
    }

    public List<ApprovalView> findAll() {
        return mapper.selectAll().stream().map(this::view).toList();
    }

    @Transactional
    public ApprovalView submit(
            String objectType,
            long objectId,
            String checksum,
            String policyCode,
            int requiredCount,
            OperatorContext operator,
            String lastModifiedBy) {
        String normalizedType = normalizeType(objectType);
        ApprovalSubjectHandler handler = handler(normalizedType);
        ApprovalSubjectHandler.ApprovalSubject subject = handler.currentSubject(objectId);
        if (subject == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Approval subject not found");
        }
        if (!requireChecksum(checksum).equals(subject.checksum())) {
            throw new PlatformException(ErrorCode.CONFLICT, "Approval checksum no longer matches the subject");
        }
        if (lastModifiedBy == null || !lastModifiedBy.equals(subject.lastModifiedBy())) {
            throw new PlatformException(ErrorCode.CONFLICT, "Approval subject modifier changed concurrently");
        }
        String normalizedPolicy = required(policyCode, "policyCode", 64).toUpperCase(Locale.ROOT);
        if (requiredCount < 1 || requiredCount > 2) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "requiredCount must be 1 or 2");
        }
        Instant now = clock.instant();
        ApprovalRequestRecord request;
        try {
            mapper.insertRequest(
                    normalizedType, objectId, checksum, normalizedPolicy, requiredCount,
                    operator.operatorId(), now);
            request = mapper.selectUnique(normalizedType, objectId, checksum, normalizedPolicy);
        } catch (DuplicateKeyException duplicate) {
            request = mapper.selectUnique(normalizedType, objectId, checksum, normalizedPolicy);
            if (request == null || !"PENDING".equals(request.status())) {
                throw new PlatformException(ErrorCode.CONFLICT, "This checksum already has a completed approval", duplicate);
            }
            return view(request);
        }
        if (request == null || handler.markPending(objectId, request.id(), checksum) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Approval subject changed concurrently");
        }
        auditService.record(
                operator, "APPROVAL_SUBMIT", normalizedType, objectId, checksum,
                null, auditView(request));
        return view(request);
    }

    @Transactional
    public ApprovalView decide(long requestId, String decision, String comment, OperatorContext operator) {
        String normalizedDecision = normalizeDecision(decision);
        ApprovalRequestRecord request = mapper.lockById(requestId);
        if (request == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Approval request not found");
        }
        if (!"PENDING".equals(request.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Approval request is already complete");
        }
        ApprovalSubjectHandler handler = handler(request.objectType());
        ApprovalSubjectHandler.ApprovalSubject subject = handler.currentSubject(request.objectId());
        if (subject == null || !request.objectChecksum().equals(subject.checksum())) {
            throw new PlatformException(ErrorCode.CONFLICT, "Approval subject checksum changed");
        }
        if (operator.operatorId().equals(subject.lastModifiedBy())) {
            throw new PlatformException(ErrorCode.FORBIDDEN, "The last modifier cannot review this version");
        }
        String normalizedComment = optional(comment, 512);
        if ("REJECT".equals(normalizedDecision) && normalizedComment == null) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "A rejection comment is required");
        }
        Instant now = clock.instant();
        try {
            mapper.insertDecision(requestId, operator.operatorId(), normalizedDecision, normalizedComment, now);
        } catch (DuplicateKeyException duplicate) {
            throw new PlatformException(ErrorCode.CONFLICT, "Reviewer has already decided this request", duplicate);
        }

        String completedStatus = null;
        if ("REJECT".equals(normalizedDecision)) {
            completedStatus = "REJECTED";
            if (handler.markRejected(request.objectId(), request.id(), request.objectChecksum()) != 1) {
                throw new PlatformException(ErrorCode.INVALID_STATE, "Approval subject changed concurrently");
            }
        } else if (mapper.countApprovals(requestId) >= request.requiredCount()) {
            completedStatus = "APPROVED";
            if (handler.markApproved(request.objectId(), request.id(), request.objectChecksum(), now) != 1) {
                throw new PlatformException(ErrorCode.INVALID_STATE, "Approval subject changed concurrently");
            }
        }
        if (completedStatus != null && mapper.complete(requestId, completedStatus, now) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Approval request changed concurrently");
        }
        ApprovalRequestRecord updated = mapper.selectById(requestId);
        auditService.record(
                operator,
                "APPROVAL_" + normalizedDecision,
                request.objectType(),
                request.objectId(),
                request.objectChecksum(),
                auditView(request),
                auditView(updated));
        return view(updated);
    }

    public ApprovalView requireApproved(
            long requestId,
            String objectType,
            long objectId,
            String checksum) {
        ApprovalRequestRecord request = mapper.selectById(requestId);
        if (request == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Approval request not found");
        }
        if (!"APPROVED".equals(request.status())
                || !normalizeType(objectType).equals(request.objectType())
                || objectId != request.objectId()
                || !requireChecksum(checksum).equals(request.objectChecksum())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Matching approved checksum is required");
        }
        return view(request);
    }

    private ApprovalSubjectHandler handler(String objectType) {
        ApprovalSubjectHandler handler = handlers.get(normalizeType(objectType));
        if (handler == null) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Unsupported approval object type");
        }
        return handler;
    }

    private ApprovalView view(ApprovalRequestRecord request) {
        return new ApprovalView(
                request.id(), request.objectType(), request.objectId(), request.objectChecksum(),
                request.policyCode(), request.requiredCount(), request.status(), request.requestedBy(),
                request.requestedAt(), request.completedAt(), mapper.selectDecisions(request.id()));
    }

    private Map<String, Object> auditView(ApprovalRequestRecord request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", request.id());
        value.put("objectType", request.objectType());
        value.put("objectId", request.objectId());
        value.put("objectChecksum", request.objectChecksum());
        value.put("policyCode", request.policyCode());
        value.put("requiredCount", request.requiredCount());
        value.put("status", request.status());
        return value;
    }

    private String normalizeType(String value) {
        return required(value, "objectType", 64).toUpperCase(Locale.ROOT);
    }

    private String normalizeDecision(String value) {
        String normalized = required(value, "decision", 16).toUpperCase(Locale.ROOT);
        if (!normalized.equals("APPROVE") && !normalized.equals("REJECT")) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "decision is invalid");
        }
        return normalized;
    }

    private String requireChecksum(String value) {
        String normalized = required(value, "checksum", 64).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "checksum must be SHA-256 hex");
        }
        return normalized;
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return value.trim();
    }

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.trim().length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "comment is too long");
        }
        return value.trim();
    }

    public record ApprovalView(
            long id,
            String objectType,
            long objectId,
            String objectChecksum,
            String policyCode,
            int requiredCount,
            String status,
            String requestedBy,
            Instant requestedAt,
            Instant completedAt,
            List<ApprovalDecisionRecord> decisions) {
    }
}
