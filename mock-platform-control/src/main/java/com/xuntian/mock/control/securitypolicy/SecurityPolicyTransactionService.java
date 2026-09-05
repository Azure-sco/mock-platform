package com.xuntian.mock.control.securitypolicy;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.outbox.ConfigPublishOutboxMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SecurityPolicyTransactionService {

    private final SecurityPolicyMapper mapper;
    private final ConfigPublishOutboxMapper outboxMapper;
    private final ApprovalService approvalService;
    private final AuditService auditService;

    public SecurityPolicyTransactionService(
            SecurityPolicyMapper mapper,
            ConfigPublishOutboxMapper outboxMapper,
            ApprovalService approvalService,
            AuditService auditService) {
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
        this.approvalService = approvalService;
        this.auditService = auditService;
    }

    @Transactional
    public SecurityPolicyVersionRecord create(PreparedDraft draft, OperatorContext operator) {
        try {
            mapper.insertVersion(
                    draft.policyId(), draft.policyType(), draft.scopeKey(), draft.versionNo(),
                    draft.protectedConfig(), draft.checksum(), draft.sourceAuditRef(), operator.operatorId());
        } catch (DuplicateKeyException duplicate) {
            throw new PlatformException(ErrorCode.CONFLICT, "An identical policy version already exists", duplicate);
        }
        SecurityPolicyVersionRecord created = mapper.selectVersionsByPolicy(draft.policyId()).stream()
                .filter(version -> version.versionNo() == draft.versionNo())
                .findFirst()
                .orElseThrow(() -> new PlatformException(ErrorCode.INTERNAL_ERROR, "Created security policy is missing"));
        auditService.record(
                operator, "SECURITY_POLICY_CREATE", SecurityPolicyApprovalHandler.OBJECT_TYPE,
                created.id(), created.checksum(), null, auditView(created));
        return created;
    }

    @Transactional
    public SecurityPolicyVersionRecord validate(
            SecurityPolicyVersionRecord expected,
            String signature,
            String signatureKeyId,
            String signatureAlgorithm,
            OperatorContext operator) {
        if (mapper.markValidated(
                expected.id(), expected.checksum(), signature, signatureKeyId, signatureAlgorithm) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Security policy changed concurrently");
        }
        SecurityPolicyVersionRecord validated = mapper.selectVersionById(expected.id());
        auditService.record(
                operator,
                "SECURITY_POLICY_VALIDATE",
                SecurityPolicyApprovalHandler.OBJECT_TYPE,
                expected.id(),
                expected.checksum(),
                auditView(expected),
                auditView(validated));
        return validated;
    }

    @Transactional
    public PublishResult publish(PreparedPublish prepared, OperatorContext operator) {
        SecurityPolicyVersionRecord policy = mapper.lockVersionById(prepared.policyVersionId());
        if (policy == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Security policy version not found");
        }
        SecurityPolicyBindingRecord current = mapper.lockBinding(policy.policyType(), policy.scopeKey());
        if ("PUBLISHED".equals(policy.status()) && current != null
                && current.desiredPolicyVersionId() == policy.id()
                && current.bindingVersion() == prepared.expectedBindingVersion() + 1) {
            return new PublishResult(policy, current);
        }
        if (!"APPROVED".equals(policy.status()) || policy.approvalRequestId() == null) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only an approved security policy can be published");
        }
        if (!policy.checksum().equals(prepared.policyChecksum())
                || !policy.policyType().equals(prepared.policyType())
                || !policy.scopeKey().equals(prepared.scopeKey())) {
            throw new PlatformException(ErrorCode.CONFLICT, "Security policy changed before publish");
        }
        approvalService.requireApproved(
                policy.approvalRequestId(),
                SecurityPolicyApprovalHandler.OBJECT_TYPE,
                policy.id(),
                policy.checksum());

        if (current == null) {
            if (prepared.expectedBindingVersion() != 0) {
                throw new PlatformException(ErrorCode.CONFLICT, "Security policy binding version changed");
            }
            try {
                mapper.insertBinding(
                        prepared.bindingId(),
                        policy.policyType(),
                        policy.scopeKey(),
                        policy.id(),
                        prepared.effectMode(),
                        prepared.publishedAt(),
                        operator.operatorId());
            } catch (DuplicateKeyException duplicate) {
                throw new PlatformException(ErrorCode.CONFLICT, "Security policy binding was created concurrently", duplicate);
            }
            current = mapper.lockBinding(policy.policyType(), policy.scopeKey());
        }
        if (current == null || !current.id().equals(prepared.bindingId())
                || current.bindingVersion() != prepared.expectedBindingVersion()) {
            throw new PlatformException(ErrorCode.CONFLICT, "Security policy binding version changed");
        }
        if (mapper.markPublished(
                policy.id(), policy.checksum(), operator.operatorId(), prepared.publishedAt()) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Security policy changed concurrently");
        }
        boolean boundImmediately = !SecurityPolicyType.APP_ACL.name().equals(policy.policyType());
        if (mapper.publishBinding(
                current.id(), policy.id(), prepared.effectMode(), prepared.expectedBindingVersion(),
                boundImmediately, prepared.publishedAt(), operator.operatorId()) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Security policy binding version changed");
        }
        if (!boundImmediately) {
            outboxMapper.insert(
                    "ADMISSION_BINDING",
                    current.id() + ":" + prepared.nextBindingVersion(),
                    "REDIS_RUNTIME_ADMISSION",
                    prepared.targetNamespace(),
                    prepared.protectedAdmissionEnvelope(),
                    prepared.admissionEnvelopeChecksum(),
                    prepared.publishedAt());
        }
        SecurityPolicyVersionRecord published = mapper.selectVersionById(policy.id());
        SecurityPolicyBindingRecord binding = mapper.selectBinding(policy.policyType(), policy.scopeKey());
        auditService.record(
                operator,
                "SECURITY_POLICY_PUBLISH",
                SecurityPolicyApprovalHandler.OBJECT_TYPE,
                policy.id(),
                policy.checksum(),
                auditView(policy),
                auditView(published));
        auditService.record(
                operator,
                "SECURITY_POLICY_BIND",
                "SECURITY_POLICY_BINDING",
                binding.id(),
                policy.checksum(),
                bindingView(current),
                bindingView(binding));
        return new PublishResult(published, binding);
    }

    private Map<String, Object> auditView(SecurityPolicyVersionRecord value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("policyId", value.policyId());
        result.put("policyType", value.policyType());
        result.put("scopeKey", value.scopeKey());
        result.put("versionNo", value.versionNo());
        result.put("checksum", value.checksum());
        result.put("status", value.status());
        return result;
    }

    private Map<String, Object> bindingView(SecurityPolicyBindingRecord value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("policyType", value.policyType());
        result.put("scopeKey", value.scopeKey());
        result.put("desiredPolicyVersionId", value.desiredPolicyVersionId());
        result.put("effectivePolicyVersionId", value.effectivePolicyVersionId());
        result.put("status", value.status());
        result.put("bindingVersion", value.bindingVersion());
        return result;
    }

    public record PreparedPublish(
            long policyVersionId,
            String policyType,
            String scopeKey,
            String policyChecksum,
            String effectMode,
            String bindingId,
            long expectedBindingVersion,
            long nextBindingVersion,
            Instant publishedAt,
            String targetNamespace,
            String protectedAdmissionEnvelope,
            String admissionEnvelopeChecksum) {
    }

    public record PublishResult(
            SecurityPolicyVersionRecord policy,
            SecurityPolicyBindingRecord binding) {
    }

    public record PreparedDraft(
            String policyId,
            String policyType,
            String scopeKey,
            int versionNo,
            String protectedConfig,
            String checksum,
            String sourceAuditRef) {
    }
}
