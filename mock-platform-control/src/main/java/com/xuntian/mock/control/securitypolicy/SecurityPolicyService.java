package com.xuntian.mock.control.securitypolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.security.PayloadSigner;
import com.xuntian.mock.control.security.ProtectedPayloadCodec;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class SecurityPolicyService {

    private static final int MAX_SCOPE_LENGTH = 512;
    private final SecurityPolicyMapper mapper;
    private final SecurityPolicyValidator validator;
    private final SecurityPolicyTransactionService transactions;
    private final ProtectedPayloadCodec payloadCodec;
    private final PayloadSigner payloadSigner;
    private final AdmissionEnvelopeFactory admissionEnvelopeFactory;
    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SecurityPolicyService(
            SecurityPolicyMapper mapper,
            SecurityPolicyValidator validator,
            SecurityPolicyTransactionService transactions,
            ProtectedPayloadCodec payloadCodec,
            PayloadSigner payloadSigner,
            AdmissionEnvelopeFactory admissionEnvelopeFactory,
            ApprovalService approvalService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mapper = mapper;
        this.validator = validator;
        this.transactions = transactions;
        this.payloadCodec = payloadCodec;
        this.payloadSigner = payloadSigner;
        this.admissionEnvelopeFactory = admissionEnvelopeFactory;
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<PolicySummary> findPolicies(String policyType, String scopeKey) {
        String normalizedType = policyType == null || policyType.isBlank()
                ? null : SecurityPolicyType.parse(policyType).name();
        String normalizedScope = scopeKey == null || scopeKey.isBlank() ? null : scope(scopeKey);
        Map<String, PolicySummary> latest = new LinkedHashMap<>();
        for (SecurityPolicyVersionRecord record : mapper.selectAll(normalizedType, normalizedScope)) {
            latest.putIfAbsent(record.policyId(), new PolicySummary(
                    record.policyId(), record.policyType(), record.scopeKey(), record.id(),
                    record.versionNo(), record.status(), record.checksum()));
        }
        return List.copyOf(latest.values());
    }

    public List<PolicyVersionView> findVersions(String policyId) {
        return mapper.selectVersionsByPolicy(policyId(policyId)).stream().map(this::view).toList();
    }

    public PolicyVersionView getVersion(long id) {
        return view(require(id));
    }

    public PolicyVersionView create(
            String existingPolicyId,
            CreateCommand command,
            OperatorContext operator) {
        SecurityPolicyType type = SecurityPolicyType.parse(command.policyType());
        String normalizedScope = scope(command.scopeKey());
        validator.validateForStorage(command.config());
        String policyId = existingPolicyId == null ? UUID.randomUUID().toString() : policyId(existingPolicyId);
        int versionNo = 1;
        if (existingPolicyId != null) {
            List<SecurityPolicyVersionRecord> existing = mapper.selectVersionsByPolicy(policyId);
            if (existing.isEmpty()) {
                throw new PlatformException(ErrorCode.NOT_FOUND, "Security policy not found");
            }
            SecurityPolicyVersionRecord latest = existing.get(0);
            if (!latest.policyType().equals(type.name()) || !latest.scopeKey().equals(normalizedScope)) {
                throw new PlatformException(ErrorCode.INVALID_REQUEST, "Policy type and scope cannot change across versions");
            }
            versionNo = mapper.nextVersionNo(policyId);
        }
        byte[] canonicalConfig = CanonicalJson.write(command.config());
        String checksum = policyChecksum(type.name(), normalizedScope, command.config());
        SecurityPolicyTransactionService.PreparedDraft draft = new SecurityPolicyTransactionService.PreparedDraft(
                policyId, type.name(), normalizedScope, versionNo,
                payloadCodec.protect(canonicalConfig), checksum,
                optional(command.sourceAuditRef(), 256));
        return view(transactions.create(draft, operator));
    }

    public PolicyVersionView validate(long id, OperatorContext operator) {
        SecurityPolicyVersionRecord policy = require(id);
        if ("VALIDATED".equals(policy.status())) {
            return view(policy);
        }
        if (!"DRAFT".equals(policy.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only a draft security policy can be validated");
        }
        JsonNode config = config(policy);
        SecurityPolicyType type = SecurityPolicyType.parse(policy.policyType());
        validator.validate(type, policy.scopeKey(), config);
        PayloadSigner.SignatureValue signature = payloadSigner.sign(
                CanonicalJson.write(signatureContent(policy.policyType(), policy.scopeKey(), policy.checksum(), config)));
        return view(transactions.validate(
                policy, signature.signature(), signature.keyId(), signature.algorithm(), operator));
    }

    public ApprovalService.ApprovalView submitApproval(
            long id,
            String policyCode,
            int requiredCount,
            OperatorContext operator) {
        SecurityPolicyVersionRecord policy = require(id);
        if (!"VALIDATED".equals(policy.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only a validated security policy can be submitted");
        }
        return approvalService.submit(
                SecurityPolicyApprovalHandler.OBJECT_TYPE,
                policy.id(),
                policy.checksum(),
                policyCode,
                requiredCount,
                operator,
                policy.createdBy());
    }

    public PublishView publish(long id, long expectedBindingVersion, OperatorContext operator) {
        if (expectedBindingVersion < 0) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "expectedBindingVersion must be >= 0");
        }
        SecurityPolicyVersionRecord policy = require(id);
        SecurityPolicyType type = SecurityPolicyType.parse(policy.policyType());
        JsonNode config = config(policy);
        validator.validate(type, policy.scopeKey(), config);
        SecurityPolicyBindingRecord binding = mapper.selectBinding(policy.policyType(), policy.scopeKey());
        if ("PUBLISHED".equals(policy.status()) && binding != null
                && binding.desiredPolicyVersionId() == policy.id()
                && binding.bindingVersion() == expectedBindingVersion + 1) {
            return new PublishView(view(policy), bindingView(binding));
        }
        if (binding != null && binding.bindingVersion() != expectedBindingVersion) {
            throw new PlatformException(ErrorCode.CONFLICT, "Security policy binding version changed");
        }
        if (binding == null && expectedBindingVersion != 0) {
            throw new PlatformException(ErrorCode.CONFLICT, "Security policy binding does not exist at the expected version");
        }
        String bindingId = binding == null ? UUID.randomUUID().toString() : binding.id();
        long nextBindingVersion = expectedBindingVersion + 1;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        AdmissionEnvelopeFactory.PreparedEnvelope admission = type == SecurityPolicyType.APP_ACL
                ? admission(bindingId, policy, nextBindingVersion, config, now)
                : null;
        SecurityPolicyTransactionService.PreparedPublish prepared =
                new SecurityPolicyTransactionService.PreparedPublish(
                        policy.id(), policy.policyType(), policy.scopeKey(), policy.checksum(),
                        type.effectMode(), bindingId, expectedBindingVersion, nextBindingVersion,
                        now,
                        admission == null ? null : admission.environment() + ":" + admission.appCode(),
                        admission == null ? null : payloadCodec.protect(admission.canonicalBytes()),
                        admission == null ? null : admission.canonicalBytesChecksum());
        SecurityPolicyTransactionService.PublishResult result = transactions.publish(prepared, operator);
        return new PublishView(view(result.policy()), bindingView(result.binding()));
    }

    public PolicyDiff diff(long id, long compareTo) {
        SecurityPolicyVersionRecord current = require(id);
        SecurityPolicyVersionRecord base = require(compareTo);
        if (!current.policyId().equals(base.policyId())) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Policy versions from different policies cannot be compared");
        }
        List<DiffEntry> changes = new ArrayList<>();
        compare("", config(base), config(current), changes);
        return new PolicyDiff(compareTo, id, List.copyOf(changes));
    }

    public SecurityPolicyVersionRecord requirePublished(long id) {
        SecurityPolicyVersionRecord policy = require(id);
        if (!"PUBLISHED".equals(policy.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Referenced security policy must be PUBLISHED");
        }
        SecurityPolicyBindingRecord binding = mapper.selectBinding(policy.policyType(), policy.scopeKey());
        if (binding == null || !"BOUND".equals(binding.status())
                || binding.desiredPolicyVersionId() != policy.id()
                || binding.effectivePolicyVersionId() == null
                || binding.effectivePolicyVersionId() != policy.id()) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Referenced security policy must be BOUND");
        }
        return policy;
    }

    public JsonNode config(SecurityPolicyVersionRecord policy) {
        try {
            return objectMapper.readTree(payloadCodec.unprotect(policy.configJsonEncrypted()));
        } catch (IOException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored security policy config is invalid", failure);
        }
    }

    public BindingView findBinding(String policyType, String scopeKey) {
        SecurityPolicyBindingRecord binding = mapper.selectBinding(
                SecurityPolicyType.parse(policyType).name(), scope(scopeKey));
        return binding == null ? null : bindingView(binding);
    }

    private AdmissionEnvelopeFactory.PreparedEnvelope admission(
            String bindingId,
            SecurityPolicyVersionRecord policy,
            long bindingVersion,
            JsonNode config,
            Instant issuedAt) {
        return admissionEnvelopeFactory.create(bindingId, policy, bindingVersion, config, issuedAt);
    }

    private String policyChecksum(String policyType, String scopeKey, JsonNode config) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policyType", policyType);
        content.put("scopeKey", scopeKey);
        content.put("config", config);
        return Checksum.sha256Hex(CanonicalJson.write(content));
    }

    private Map<String, Object> signatureContent(
            String policyType,
            String scopeKey,
            String checksum,
            JsonNode config) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policyType", policyType);
        content.put("scopeKey", scopeKey);
        content.put("checksum", checksum);
        content.put("config", config);
        return content;
    }

    private SecurityPolicyVersionRecord require(long id) {
        SecurityPolicyVersionRecord policy = mapper.selectVersionById(id);
        if (policy == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Security policy version not found");
        }
        return policy;
    }

    private PolicyVersionView view(SecurityPolicyVersionRecord policy) {
        return new PolicyVersionView(
                policy.id(), policy.policyId(), policy.policyType(), policy.scopeKey(), policy.versionNo(),
                policy.status(), config(policy), policy.checksum(), policy.signature(), policy.signatureKeyId(),
                policy.signatureAlgorithm(), policy.sourceAuditRef(), policy.approvalRequestId(),
                policy.createdBy(), policy.createdAt(),
                policy.publishedBy(), policy.publishedAt());
    }

    private BindingView bindingView(SecurityPolicyBindingRecord binding) {
        return new BindingView(
                binding.id(), binding.policyType(), binding.scopeKey(), binding.desiredPolicyVersionId(),
                binding.effectivePolicyVersionId(), binding.effectMode(), binding.status(),
                binding.bindingVersion(), binding.desiredAt(), binding.boundAt(),
                binding.sdkEffectiveConfigVersion(), binding.effectiveAt());
    }

    private Map<String, Object> auditView(SecurityPolicyVersionRecord policy) {
        return Map.of(
                "id", policy.id(),
                "policyId", policy.policyId(),
                "policyType", policy.policyType(),
                "scopeKey", policy.scopeKey(),
                "versionNo", policy.versionNo(),
                "status", policy.status(),
                "checksum", policy.checksum());
    }

    private String policyId(String value) {
        return required(value, "policyId", 64);
    }

    private String scope(String value) {
        String normalized = required(value, "scopeKey", MAX_SCOPE_LENGTH);
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "scopeKey is invalid");
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
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "sourceAuditRef is invalid");
        }
        return value.trim();
    }

    private void compare(String path, JsonNode before, JsonNode after, List<DiffEntry> changes) {
        if (before == null || before.isMissingNode() || before.isNull()) {
            if (after != null && !after.isNull()) changes.add(new DiffEntry(display(path), "ADDED", before, after));
            return;
        }
        if (after == null || after.isMissingNode() || after.isNull()) {
            changes.add(new DiffEntry(display(path), "REMOVED", before, after));
            return;
        }
        if (before.isObject() && after.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            before.fieldNames().forEachRemaining(names::add);
            after.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> compare(path + "/" + escape(name), before.get(name), after.get(name), changes));
            return;
        }
        if (before.isArray() && after.isArray()) {
            int size = Math.max(before.size(), after.size());
            for (int index = 0; index < size; index++) {
                compare(path + "/" + index, before.path(index), after.path(index), changes);
            }
            return;
        }
        if (!before.equals(after)) changes.add(new DiffEntry(display(path), "CHANGED", before, after));
    }

    private String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private String display(String path) {
        return path.isEmpty() ? "/" : path;
    }

    public record CreateCommand(
            String policyType,
            String scopeKey,
            JsonNode config,
            String sourceAuditRef) {
    }

    public record PolicySummary(
            String policyId,
            String policyType,
            String scopeKey,
            long latestVersionId,
            int latestVersionNo,
            String latestStatus,
            String latestChecksum) {
    }

    public record PolicyVersionView(
            long id,
            String policyId,
            String policyType,
            String scopeKey,
            int versionNo,
            String status,
            JsonNode config,
            String checksum,
            String signature,
            String signatureKeyId,
            String signatureAlgorithm,
            String sourceAuditRef,
            Long approvalRequestId,
            String createdBy,
            Instant createdAt,
            String publishedBy,
            Instant publishedAt) {
    }

    public record BindingView(
            String id,
            String policyType,
            String scopeKey,
            long desiredPolicyVersionId,
            Long effectivePolicyVersionId,
            String effectMode,
            String status,
            long bindingVersion,
            Instant desiredAt,
            Instant boundAt,
            Long sdkEffectiveConfigVersion,
            Instant effectiveAt) {
    }

    public record PublishView(PolicyVersionView policy, BindingView binding) {
    }

    public record DiffEntry(String path, String changeType, JsonNode before, JsonNode after) {
    }

    public record PolicyDiff(long compareTo, long policyVersionId, List<DiffEntry> changes) {
    }

}
