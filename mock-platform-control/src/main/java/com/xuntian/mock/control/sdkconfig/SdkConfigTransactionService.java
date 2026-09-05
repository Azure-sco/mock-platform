package com.xuntian.mock.control.sdkconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.outbox.ConfigPublishOutboxMapper;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class SdkConfigTransactionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SdkConfigMapper mapper;
    private final ConfigPublishOutboxMapper outboxMapper;
    private final SecurityPolicyMapper securityPolicyMapper;
    private final ApprovalService approvalService;
    private final AuditService auditService;

    public SdkConfigTransactionService(
            SdkConfigMapper mapper,
            ConfigPublishOutboxMapper outboxMapper,
            SecurityPolicyMapper securityPolicyMapper,
            ApprovalService approvalService,
            AuditService auditService) {
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
        this.securityPolicyMapper = securityPolicyMapper;
        this.approvalService = approvalService;
        this.auditService = auditService;
    }

    @Transactional
    public SdkConfigEnvelopeRecord create(PreparedDraft draft, OperatorContext operator) {
        try {
            mapper.insertEnvelope(
                    draft.appCode(), draft.environment(), draft.configVersion(), draft.routingJson(),
                    draft.policyRefsJson(), draft.protectedPolicyPayloads(), draft.effectiveAt(), draft.expireAt(),
                    draft.checksum(), draft.sourceAuditRef(), operator.operatorId());
        } catch (DuplicateKeyException duplicate) {
            throw new PlatformException(ErrorCode.CONFLICT, "SDK config version or checksum already exists", duplicate);
        }
        SdkConfigEnvelopeRecord created = mapper.selectEnvelopes(draft.appCode(), draft.environment()).stream()
                .filter(item -> item.configVersion() == draft.configVersion()).findFirst()
                .orElseThrow(() -> new PlatformException(ErrorCode.INTERNAL_ERROR, "Created SDK config is missing"));
        auditService.record(operator, "SDK_CONFIG_CREATE", SdkConfigApprovalHandler.OBJECT_TYPE,
                created.id(), created.checksum(), null, auditView(created));
        return created;
    }

    @Transactional
    public SdkConfigEnvelopeRecord validate(
            SdkConfigEnvelopeRecord expected,
            String signature,
            String signatureKeyId,
            String signatureAlgorithm,
            OperatorContext operator) {
        if (mapper.markValidated(
                expected.id(), expected.checksum(), signature, signatureKeyId, signatureAlgorithm) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "SDK config changed concurrently");
        }
        SdkConfigEnvelopeRecord validated = mapper.selectEnvelope(expected.id());
        auditService.record(operator, "SDK_CONFIG_VALIDATE", SdkConfigApprovalHandler.OBJECT_TYPE,
                expected.id(), expected.checksum(), auditView(expected), auditView(validated));
        return validated;
    }

    @Transactional
    public PublishResult publish(PreparedActivation prepared, OperatorContext operator) {
        SdkConfigActivationRecord existingRequest = mapper.selectActivationByRequest(prepared.requestId());
        if (existingRequest != null) {
            if (existingRequest.sdkConfigEnvelopeId() != prepared.envelopeId()) {
                throw new PlatformException(ErrorCode.CONFLICT, "Idempotency-Key belongs to another SDK activation");
            }
            return result(existingRequest);
        }
        SdkConfigEnvelopeRecord envelope = mapper.lockEnvelope(prepared.envelopeId());
        if (envelope == null) throw new PlatformException(ErrorCode.NOT_FOUND, "SDK config envelope not found");
        if (!"APPROVED".equals(envelope.status()) || envelope.approvalRequestId() == null) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only an approved SDK config can be published");
        }
        if (!envelope.checksum().equals(prepared.envelopeChecksum())
                || envelope.configVersion() != prepared.toConfigVersion()) {
            throw new PlatformException(ErrorCode.CONFLICT, "SDK config changed before activation");
        }
        approvalService.requireApproved(envelope.approvalRequestId(), SdkConfigApprovalHandler.OBJECT_TYPE,
                envelope.id(), envelope.checksum());
        ActiveSdkConfigRecord active = mapper.lockActive(envelope.appCode(), envelope.environment());
        long actualVersion = active == null ? 0 : active.desiredConfigVersion();
        if (actualVersion != prepared.expectedConfigVersion()) {
            throw new PlatformException(ErrorCode.CONFLICT, "expectedConfigVersion does not match MySQL authority");
        }
        if (active != null && "ACTIVATING".equals(active.state())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "An SDK config activation is already in progress");
        }
        boolean partialRecovery = active != null && "PARTIAL".equals(active.state())
                && prepared.partialRecovery() && matchesLastApplied(envelope, active);
        if (active != null && "PARTIAL".equals(active.state()) && !partialRecovery) {
            throw new PlatformException(
                    ErrorCode.INVALID_STATE,
                    "PARTIAL blocks publish except a new version copied from the last applied Envelope");
        }
        if (envelope.configVersion() <= actualVersion) {
            throw new PlatformException(ErrorCode.CONFLICT, "SDK configVersion must increase monotonically");
        }
        if (mapper.markPublishing(envelope.id(), envelope.checksum()) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "SDK config changed concurrently");
        }
        mapper.insertActivation(
                prepared.activationId(), envelope.appCode(), envelope.environment(), envelope.id(),
                active == null ? null : active.desiredConfigVersion(), envelope.configVersion(),
                prepared.requestId(), operator.operatorId(), prepared.publishedAt());
        for (String instanceId : prepared.targetInstances()) {
            mapper.insertTarget(prepared.activationId(), instanceId, prepared.publishedAt());
        }
        if (active == null) {
            mapper.insertActive(envelope.appCode(), envelope.environment(), envelope.id(), envelope.configVersion(),
                    prepared.activationId(), prepared.publishedAt());
        } else if (mapper.updateActiveDesired(
                envelope.appCode(), envelope.environment(), envelope.id(), envelope.configVersion(),
                prepared.expectedConfigVersion(), prepared.activationId(), prepared.publishedAt(),
                partialRecovery) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Active SDK config changed concurrently");
        }
        outboxMapper.insert(
                "SDK_CONFIG_ACTIVATION", prepared.activationId(), prepared.targetType(),
                prepared.targetNamespace(), prepared.protectedWrapper(), prepared.wrapperBytesChecksum(),
                prepared.publishedAt());
        auditService.record(operator, "SDK_CONFIG_PUBLISH", SdkConfigApprovalHandler.OBJECT_TYPE,
                envelope.id(), envelope.checksum(), auditView(envelope), Map.of(
                        "activationId", prepared.activationId(),
                        "configVersion", envelope.configVersion(),
                        "status", "PUBLISHING",
                        "targetCount", prepared.targetInstances().size()));
        return result(mapper.selectActivation(prepared.activationId()));
    }

    @Transactional
    public void markOutboxProjected(String activationId, Instant now) {
        SdkConfigActivationRecord activation = mapper.selectActivation(activationId);
        if (activation == null) return;
        mapper.markActivationProjected(activationId);
        mapper.markPublished(activation.sdkConfigEnvelopeId(), activation.operator(), now);
    }

    @Transactional
    public AckResult recordEvent(SdkConfigEvent event, Instant receivedAt) {
        SdkConfigActivationRecord activation = mapper.selectActivation(event.activationId());
        if (activation == null) throw new PlatformException(ErrorCode.NOT_FOUND, "SDK config activation not found");
        if ("PENDING".equals(activation.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "SDK config has not been projected yet");
        }
        if (!activation.appCode().equals(event.appCode())
                || !activation.environment().equals(event.environment())
                || activation.sdkConfigEnvelopeId() != event.envelopeId()
                || activation.toConfigVersion() != event.newConfigVersion()) {
            throw new PlatformException(ErrorCode.CONFLICT, "SDK event does not match its activation");
        }
        SdkConfigEnvelopeRecord eventEnvelope = mapper.selectEnvelope(activation.sdkConfigEnvelopeId());
        if (eventEnvelope == null || !sameJson(
                eventEnvelope.securityPolicyRefsJson(), event.securityPolicyRefsJson())) {
            throw new PlatformException(ErrorCode.CONFLICT, "SDK event securityPolicyRefs do not match the Envelope");
        }
        if (mapper.countMatchingEvent(
                event.activationId(), event.sdkInstanceId(), event.status(), event.newConfigVersion()) > 0) {
            return new AckResult(
                    activation, mapper.selectTargets(activation.id()), "APPLIED".equals(activation.status()));
        }
        ActiveSdkConfigRecord active = mapper.lockActive(activation.appCode(), activation.environment());
        if (active == null || !active.activationId().equals(activation.id())) {
            throw new PlatformException(ErrorCode.CONFLICT, "SDK event is not for the current MySQL desired config");
        }
        try {
            mapper.insertEvent(
                    event.appCode(), event.environment(), event.sdkInstanceId(), event.envelopeId(),
                    event.activationId(), event.oldConfigVersion(), event.newConfigVersion(),
                    event.securityPolicyRefsJson(), event.status(), event.effectiveAt(),
                    event.errorMasked(), event.sourceAuditRef(), receivedAt);
        } catch (DuplicateKeyException duplicate) {
            throw new PlatformException(ErrorCode.CONFLICT, "SDK instance already reported a different result", duplicate);
        }
        if (mapper.updateTargetFromEvent(
                event.activationId(), event.sdkInstanceId(), event.status(), receivedAt) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "SDK instance is not a required activation target");
        }
        return aggregate(activation, receivedAt);
    }

    @Transactional
    public AckResult waiveTarget(
            String activationId,
            String instanceId,
            String reason,
            Instant now,
            OperatorContext operator) {
        SdkConfigActivationRecord activation = mapper.selectActivation(activationId);
        if (activation == null) throw new PlatformException(ErrorCode.NOT_FOUND, "SDK config activation not found");
        mapper.lockActive(activation.appCode(), activation.environment());
        if (mapper.waiveTarget(activationId, instanceId, operator.operatorId(), reason, now) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "SDK target cannot be waived in its current state");
        }
        auditService.record(operator, "SDK_CONFIG_TARGET_WAIVE", "SDK_CONFIG_TARGET",
                activationId + ":" + instanceId, null, null, Map.of("reason", reason));
        return aggregate(activation, now);
    }

    @Transactional
    public AckResult markLeft(String activationId, String instanceId, Instant now, OperatorContext operator) {
        SdkConfigActivationRecord activation = mapper.selectActivation(activationId);
        if (activation == null) throw new PlatformException(ErrorCode.NOT_FOUND, "SDK config activation not found");
        mapper.lockActive(activation.appCode(), activation.environment());
        if (mapper.markTargetLeft(activationId, instanceId, now) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "SDK target cannot be marked LEFT");
        }
        auditService.record(operator, "SDK_CONFIG_TARGET_LEFT", "SDK_CONFIG_TARGET",
                activationId + ":" + instanceId, null, null, Map.of("status", "LEFT"));
        return aggregate(activation, now);
    }

    @Transactional
    public void markPartial(String activationId, Instant now) {
        SdkConfigActivationRecord activation = mapper.selectActivation(activationId);
        if (activation == null) return;
        mapper.lockActive(activation.appCode(), activation.environment());
        mapper.markActivationPartial(activationId);
        mapper.markActivePartial(activationId, now);
    }

    private AckResult aggregate(SdkConfigActivationRecord activation, Instant now) {
        List<SdkConfigTargetRecord> targets = mapper.selectTargets(activation.id());
        boolean complete = !targets.isEmpty() && targets.stream().allMatch(target ->
                (target.required() && "APPLIED".equals(target.status()))
                        || (!target.required() && Set.of("LEFT", "WAIVED").contains(target.status())));
        if (complete) {
            mapper.markActivationApplied(activation.id(), now);
            mapper.markActiveApplied(activation.id(), now);
            SdkConfigEnvelopeRecord envelope = mapper.selectEnvelope(activation.sdkConfigEnvelopeId());
            List<Long> policyIds = SdkConfigService.policyVersionIds(envelope.securityPolicyRefsJson());
            if (!policyIds.isEmpty()) {
                securityPolicyMapper.markSdkPoliciesEffective(policyIds, activation.toConfigVersion(), now, "sdk-ack-aggregator");
            }
            activation = mapper.selectActivation(activation.id());
        }
        return new AckResult(activation, mapper.selectTargets(activation.id()), complete);
    }

    private PublishResult result(SdkConfigActivationRecord activation) {
        return new PublishResult(
                activation,
                mapper.selectTargets(activation.id()),
                mapper.selectActive(activation.appCode(), activation.environment()));
    }

    private boolean matchesLastApplied(SdkConfigEnvelopeRecord candidate, ActiveSdkConfigRecord active) {
        if (active.lastAppliedEnvelopeId() == null) return false;
        SdkConfigEnvelopeRecord applied = mapper.selectEnvelope(active.lastAppliedEnvelopeId());
        return applied != null
                && candidate.appCode().equals(applied.appCode())
                && candidate.environment().equals(applied.environment())
                && candidate.routingJson().equals(applied.routingJson())
                && candidate.securityPolicyRefsJson().equals(applied.securityPolicyRefsJson())
                && candidate.securityPolicyPayloadsEncrypted().equals(applied.securityPolicyPayloadsEncrypted())
                && candidate.effectiveAt().equals(applied.effectiveAt())
                && Objects.equals(candidate.expireAt(), applied.expireAt());
    }

    private boolean sameJson(String left, String right) {
        try {
            JsonNode leftNode = JSON.readTree(left);
            JsonNode rightNode = JSON.readTree(right);
            return java.util.Arrays.equals(CanonicalJson.write(leftNode), CanonicalJson.write(rightNode));
        } catch (IOException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored SDK policy refs are invalid", failure);
        }
    }

    private Map<String, Object> auditView(SdkConfigEnvelopeRecord value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("appCode", value.appCode());
        result.put("environment", value.environment());
        result.put("configVersion", value.configVersion());
        result.put("checksum", value.checksum());
        result.put("status", value.status());
        return result;
    }

    public record PreparedDraft(
            String appCode, String environment, long configVersion, String routingJson,
            String policyRefsJson, String protectedPolicyPayloads, Instant effectiveAt,
            Instant expireAt, String checksum, String sourceAuditRef) {
    }

    public record PreparedActivation(
            String activationId, long envelopeId, String envelopeChecksum, long toConfigVersion,
            long expectedConfigVersion, String requestId, Instant publishedAt, Set<String> targetInstances,
            String targetType, String targetNamespace, String protectedWrapper, String wrapperBytesChecksum,
            boolean partialRecovery) {
    }

    public record SdkConfigEvent(
            String activationId, String appCode, String environment, String sdkInstanceId,
            long envelopeId, Long oldConfigVersion, long newConfigVersion,
            String securityPolicyRefsJson, String status, Instant effectiveAt,
            String errorMasked, String sourceAuditRef) {
    }

    public record PublishResult(
            SdkConfigActivationRecord activation,
            List<SdkConfigTargetRecord> targets,
            ActiveSdkConfigRecord active) {
    }

    public record AckResult(
            SdkConfigActivationRecord activation,
            List<SdkConfigTargetRecord> targets,
            boolean applied) {
    }
}
