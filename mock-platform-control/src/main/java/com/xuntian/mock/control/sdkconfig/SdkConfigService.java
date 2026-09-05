package com.xuntian.mock.control.sdkconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.common.RequestIds;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.security.PayloadSigner;
import com.xuntian.mock.control.security.ProtectedPayloadCodec;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyService;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyType;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyVersionRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SdkConfigService {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private final SdkConfigMapper mapper;
    private final SdkConfigValidator validator;
    private final SdkConfigTransactionService transactions;
    private final SecurityPolicyService securityPolicyService;
    private final ProtectedPayloadCodec payloadCodec;
    private final PayloadSigner payloadSigner;
    private final SdkInstanceDiscoveryPort discovery;
    private final ApprovalService approvalService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SdkConfigService(
            SdkConfigMapper mapper,
            SdkConfigValidator validator,
            SdkConfigTransactionService transactions,
            SecurityPolicyService securityPolicyService,
            ProtectedPayloadCodec payloadCodec,
            PayloadSigner payloadSigner,
            SdkInstanceDiscoveryPort discovery,
            ApprovalService approvalService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mapper = mapper;
        this.validator = validator;
        this.transactions = transactions;
        this.securityPolicyService = securityPolicyService;
        this.payloadCodec = payloadCodec;
        this.payloadSigner = payloadSigner;
        this.discovery = discovery;
        this.approvalService = approvalService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<EnvelopeView> find(String appCode, String environment) {
        return mapper.selectEnvelopes(app(appCode), environment(environment)).stream().map(this::view).toList();
    }

    public EnvelopeView get(long id) {
        return view(require(id));
    }

    public EnvelopeView create(CreateCommand command, OperatorContext operator) {
        String appCode = app(command.appCode());
        String environment = environment(command.environment());
        if (command.routing() == null || !command.routing().isObject()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "routing must be a JSON object");
        }
        List<SdkConfigValidator.PolicyBundle> policies = policies(command.securityPolicyVersionIds());
        long configVersion = mapper.nextConfigVersion(appCode, environment);
        Instant effectiveAt = command.effectiveAt() == null ? clock.instant() : command.effectiveAt();
        ArrayNode refs = refs(policies);
        ObjectNode payloads = payloads(policies);
        String checksum = checksum(
                appCode, environment, configVersion, command.routing(), refs, payloads,
                effectiveAt, command.expireAt());
        SdkConfigTransactionService.PreparedDraft draft = new SdkConfigTransactionService.PreparedDraft(
                appCode,
                environment,
                configVersion,
                json(command.routing()),
                json(refs),
                payloadCodec.protect(CanonicalJson.write(payloads)),
                effectiveAt,
                command.expireAt(),
                checksum,
                optional(command.sourceAuditRef(), 256));
        return view(transactions.create(draft, operator));
    }

    public EnvelopeView validate(long id, OperatorContext operator) {
        SdkConfigEnvelopeRecord envelope = require(id);
        if ("VALIDATED".equals(envelope.status())) return view(envelope);
        if (!"DRAFT".equals(envelope.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only a draft SDK config can be validated");
        }
        JsonNode routing = parse(envelope.routingJson(), "routing");
        List<SdkConfigValidator.PolicyBundle> policies = policies(policyVersionIds(envelope.securityPolicyRefsJson()));
        validator.validate(
                envelope.appCode(), envelope.environment(), routing, policies,
                envelope.effectiveAt(), envelope.expireAt());
        PayloadSigner.SignatureValue signature = payloadSigner.sign(CanonicalJson.write(snapshot(envelope)));
        return view(transactions.validate(
                envelope, signature.signature(), signature.keyId(), signature.algorithm(), operator));
    }

    public ApprovalService.ApprovalView submitApproval(
            long id,
            String policyCode,
            int requiredCount,
            OperatorContext operator) {
        SdkConfigEnvelopeRecord envelope = require(id);
        if (!"VALIDATED".equals(envelope.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only a validated SDK config can be submitted");
        }
        return approvalService.submit(
                SdkConfigApprovalHandler.OBJECT_TYPE,
                envelope.id(),
                envelope.checksum(),
                policyCode,
                requiredCount,
                operator,
                envelope.createdBy());
    }

    public SdkConfigTransactionService.PublishResult publish(
            long id,
            PublishCommand command,
            String idempotencyKey,
            OperatorContext operator) {
        if (command.expectedConfigVersion() < 0) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "expectedConfigVersion must be >= 0");
        }
        SdkConfigActivationRecord existing = mapper.selectActivationByRequest(
                required(idempotencyKey, "Idempotency-Key", 128));
        if (existing != null) {
            if (existing.sdkConfigEnvelopeId() != id) {
                throw new PlatformException(ErrorCode.CONFLICT, "Idempotency-Key belongs to another SDK activation");
            }
            return new SdkConfigTransactionService.PublishResult(
                    existing, mapper.selectTargets(existing.id()),
                    mapper.selectActive(existing.appCode(), existing.environment()));
        }
        SdkConfigEnvelopeRecord envelope = require(id);
        if (!"APPROVED".equals(envelope.status()) || envelope.approvalRequestId() == null) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only an approved SDK config can be published");
        }
        approvalService.requireApproved(
                envelope.approvalRequestId(), SdkConfigApprovalHandler.OBJECT_TYPE,
                envelope.id(), envelope.checksum());
        List<SdkConfigValidator.PolicyBundle> policies = policies(policyVersionIds(envelope.securityPolicyRefsJson()));
        validator.validate(
                envelope.appCode(), envelope.environment(), parse(envelope.routingJson(), "routing"), policies,
                envelope.effectiveAt(), envelope.expireAt());
        ActiveSdkConfigRecord active = mapper.selectActive(envelope.appCode(), envelope.environment());
        long actualVersion = active == null ? 0 : active.desiredConfigVersion();
        if (actualVersion != command.expectedConfigVersion()) {
            throw new PlatformException(ErrorCode.CONFLICT, "expectedConfigVersion does not match MySQL authority");
        }
        if (active != null && "ACTIVATING".equals(active.state())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "An SDK config activation is already in progress");
        }
        boolean partialRecovery = active != null && "PARTIAL".equals(active.state())
                && matchesLastApplied(envelope, active);
        if (active != null && "PARTIAL".equals(active.state()) && !partialRecovery) {
            throw new PlatformException(
                    ErrorCode.INVALID_STATE,
                    "PARTIAL blocks publish except a new version copied from the last applied Envelope");
        }
        if (!discovery.available()) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "SDK discovery/governance adapter is not configured");
        }
        Set<String> targets = new TreeSet<>(
                discovery.registeredReadyInstances(envelope.appCode(), envelope.environment()));
        if (targets.isEmpty()) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "No REGISTERED + READY SDK instances were discovered");
        }
        String targetType = targetType(command.targetType());
        String namespace = required(command.targetNamespace(), "targetNamespace", 256);
        String activationId = UUID.randomUUID().toString();
        Instant publishedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        PreparedWrapper wrapper = wrapper(activationId, envelope, publishedAt);
        return transactions.publish(
                new SdkConfigTransactionService.PreparedActivation(
                        activationId,
                        envelope.id(),
                        envelope.checksum(),
                        envelope.configVersion(),
                        command.expectedConfigVersion(),
                        idempotencyKey,
                        publishedAt,
                        Set.copyOf(targets),
                        targetType,
                        namespace,
                        payloadCodec.protect(wrapper.bytes()),
                        Checksum.sha256Hex(wrapper.bytes()),
                        partialRecovery),
                operator);
    }

    public EnvelopeView rollback(long historicalEnvelopeId, OperatorContext operator) {
        SdkConfigEnvelopeRecord historical = require(historicalEnvelopeId);
        long configVersion = mapper.nextConfigVersion(historical.appCode(), historical.environment());
        JsonNode routing = parse(historical.routingJson(), "routing");
        JsonNode refs = parse(historical.securityPolicyRefsJson(), "securityPolicyRefs");
        JsonNode payloads = policyPayloads(historical);
        String checksum = checksum(
                historical.appCode(), historical.environment(), configVersion, routing, refs, payloads,
                historical.effectiveAt(), historical.expireAt());
        SdkConfigTransactionService.PreparedDraft draft = new SdkConfigTransactionService.PreparedDraft(
                historical.appCode(), historical.environment(), configVersion, json(routing), json(refs),
                historical.securityPolicyPayloadsEncrypted(), historical.effectiveAt(), historical.expireAt(),
                checksum, historical.sourceAuditRef());
        return view(transactions.create(draft, operator));
    }

    public EnvelopeDiff diff(long id, long compareTo) {
        SdkConfigEnvelopeRecord current = require(id);
        SdkConfigEnvelopeRecord base = require(compareTo);
        if (!current.appCode().equals(base.appCode()) || !current.environment().equals(base.environment())) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "SDK configs from different scopes cannot be compared");
        }
        JsonNode before = comparable(base);
        JsonNode after = comparable(current);
        List<DiffEntry> changes = new ArrayList<>();
        compare("", before, after, changes);
        return new EnvelopeDiff(compareTo, id, List.copyOf(changes));
    }

    public SdkConfigTransactionService.AckResult recordEvent(EventCommand command) {
        String status = required(command.status(), "status", 16).toUpperCase(Locale.ROOT);
        if (!Set.of("APPLIED", "REJECTED").contains(status)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "SDK event status is invalid");
        }
        if ("REJECTED".equals(status) && (command.errorMasked() == null || command.errorMasked().isBlank())) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Rejected SDK event requires errorMasked");
        }
        if (command.securityPolicyRefs() == null || !command.securityPolicyRefs().isArray()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "securityPolicyRefs must be an array");
        }
        return transactions.recordEvent(
                new SdkConfigTransactionService.SdkConfigEvent(
                        required(command.activationId(), "activationId", 64),
                        app(command.appCode()),
                        environment(command.environment()),
                        required(command.sdkInstanceId(), "sdkInstanceId", 128),
                        command.envelopeId(),
                        command.oldConfigVersion(),
                        command.newConfigVersion(),
                        json(command.securityPolicyRefs()),
                        status,
                        command.effectiveAt(),
                        optional(command.errorMasked(), 512),
                        optional(command.sourceAuditRef(), 256)),
                clock.instant());
    }

    public SdkConfigTransactionService.AckResult waiveTarget(
            String activationId,
            String instanceId,
            String reason,
            OperatorContext operator) {
        SdkConfigActivationRecord activation = activation(activationId);
        String normalizedReason = required(reason, "waiveReason", 512);
        if (!discovery.removedFromTraffic(activation.appCode(), activation.environment(), instanceId)) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Target must be proven removed from traffic before WAIVE");
        }
        return transactions.waiveTarget(
                activationId, required(instanceId, "sdkInstanceId", 128), normalizedReason,
                clock.instant(), operator);
    }

    public void reconcileTargets() {
        Instant now = clock.instant();
        OperatorContext system = new OperatorContext("sdk-target-reconciler", Set.of("SYSTEM"), RequestIds.generate());
        for (SdkConfigActivationRecord activation : mapper.selectTimedOutActivations(now.minusSeconds(60))) {
            for (SdkConfigTargetRecord target : mapper.selectTargets(activation.id())) {
                if (!target.required() || "APPLIED".equals(target.status())) continue;
                discovery.removeFromTraffic(activation.appCode(), activation.environment(), target.sdkInstanceId());
                if (discovery.deregisteredFor(
                        activation.appCode(), activation.environment(), target.sdkInstanceId(), Duration.ofSeconds(10))) {
                    transactions.markLeft(activation.id(), target.sdkInstanceId(), now, system);
                }
            }
            transactions.markPartial(activation.id(), now);
        }
    }

    public SdkConfigTransactionService.PublishResult activationView(String activationId) {
        SdkConfigActivationRecord activation = activation(activationId);
        return new SdkConfigTransactionService.PublishResult(
                activation,
                mapper.selectTargets(activationId),
                mapper.selectActive(activation.appCode(), activation.environment()));
    }

    private List<SdkConfigValidator.PolicyBundle> policies(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        LinkedHashSet<Long> unique = new LinkedHashSet<>(ids);
        if (unique.size() != ids.size() || unique.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "securityPolicyVersionIds contains duplicates or invalid IDs");
        }
        List<SdkConfigValidator.PolicyBundle> result = new ArrayList<>();
        for (Long id : unique) {
            SecurityPolicyVersionRecord policy = securityPolicyService.requirePublished(id);
            SecurityPolicyType type = SecurityPolicyType.parse(policy.policyType());
            if (type != SecurityPolicyType.SDK_HEADER_FILTER && type != SecurityPolicyType.SDK_FALLBACK_REAL) {
                throw new PlatformException(ErrorCode.INVALID_REQUEST, "SDK Envelope may only embed SDK security policies");
            }
            JsonNode config = securityPolicyService.config(policy);
            result.add(new SdkConfigValidator.PolicyBundle(
                    policy.id(), policy.policyType(), policy.scopeKey(),
                    Checksum.sha256Hex(CanonicalJson.write(config)), config));
        }
        return List.copyOf(result);
    }

    private ArrayNode refs(List<SdkConfigValidator.PolicyBundle> policies) {
        ArrayNode refs = objectMapper.createArrayNode();
        policies.forEach(policy -> {
            ObjectNode ref = refs.addObject();
            ref.put("policyVersionId", policy.policyVersionId());
            ref.put("policyType", policy.policyType());
            ref.put("scopeKey", policy.scopeKey());
            ref.put("checksum", policy.checksum());
        });
        return refs;
    }

    private ObjectNode payloads(List<SdkConfigValidator.PolicyBundle> policies) {
        ObjectNode payloads = objectMapper.createObjectNode();
        policies.forEach(policy -> payloads.set(Long.toString(policy.policyVersionId()), policy.config()));
        return payloads;
    }

    private PreparedWrapper wrapper(String activationId, SdkConfigEnvelopeRecord envelope, Instant publishedAt) {
        Map<String, Object> activation = new LinkedHashMap<>();
        activation.put("schemaVersion", "1");
        activation.put("activationId", activationId);
        activation.put("appCode", envelope.appCode());
        activation.put("environment", envelope.environment());
        activation.put("envelopeId", Long.toString(envelope.id()));
        activation.put("configVersion", envelope.configVersion());
        activation.put("envelopeChecksum", envelope.checksum());
        activation.put("publishedAt", publishedAt.toString());
        activation.put("envelope", fullEnvelope(envelope));
        byte[] activationBytes = CanonicalJson.write(activation);
        String wrapperChecksum = Checksum.sha256Hex(activationBytes);
        PayloadSigner.SignatureValue signature = payloadSigner.sign(activationBytes);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("activation", activation);
        wrapper.put("wrapperChecksum", wrapperChecksum);
        wrapper.put("wrapperSignature", signature.signature());
        wrapper.put("wrapperSignatureKeyId", signature.keyId());
        wrapper.put("wrapperSignatureAlgorithm", signature.algorithm());
        return new PreparedWrapper(CanonicalJson.write(wrapper));
    }

    private Map<String, Object> fullEnvelope(SdkConfigEnvelopeRecord envelope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshot", snapshot(envelope));
        result.put("checksum", envelope.checksum());
        result.put("signature", envelope.signature());
        result.put("signatureKeyId", envelope.signatureKeyId());
        result.put("signatureAlgorithm", envelope.signatureAlgorithm());
        return result;
    }

    private Map<String, Object> snapshot(SdkConfigEnvelopeRecord envelope) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", "1");
        content.put("appCode", envelope.appCode());
        content.put("environment", envelope.environment());
        content.put("configVersion", envelope.configVersion());
        content.put("routing", parse(envelope.routingJson(), "routing"));
        content.put("securityPolicyRefs", parse(envelope.securityPolicyRefsJson(), "securityPolicyRefs"));
        content.put("securityPolicyPayloads", policyPayloads(envelope));
        content.put("effectiveAt", envelope.effectiveAt().toString());
        content.put("expireAt", envelope.expireAt() == null ? null : envelope.expireAt().toString());
        return content;
    }

    private JsonNode comparable(SdkConfigEnvelopeRecord envelope) {
        return objectMapper.valueToTree(snapshot(envelope));
    }

    private JsonNode policyPayloads(SdkConfigEnvelopeRecord envelope) {
        try {
            return objectMapper.readTree(payloadCodec.unprotect(envelope.securityPolicyPayloadsEncrypted()));
        } catch (IOException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored SDK policy payloads are invalid", failure);
        }
    }

    private EnvelopeView view(SdkConfigEnvelopeRecord envelope) {
        return new EnvelopeView(
                envelope.id(), envelope.appCode(), envelope.environment(), envelope.configVersion(),
                parse(envelope.routingJson(), "routing"), parse(envelope.securityPolicyRefsJson(), "securityPolicyRefs"),
                policyPayloads(envelope), envelope.effectiveAt(), envelope.expireAt(), envelope.checksum(),
                envelope.signature(), envelope.signatureKeyId(), envelope.signatureAlgorithm(),
                envelope.validationStatus(), envelope.status(),
                envelope.approvalRequestId(), envelope.sourceAuditRef(), envelope.createdBy(), envelope.createdAt(),
                envelope.publishedBy(), envelope.publishedAt());
    }

    private String checksum(
            String appCode, String environment, long configVersion, JsonNode routing,
            JsonNode refs, JsonNode payloads, Instant effectiveAt, Instant expireAt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", "1");
        content.put("appCode", appCode);
        content.put("environment", environment);
        content.put("configVersion", configVersion);
        content.put("routing", routing);
        content.put("securityPolicyRefs", refs);
        content.put("securityPolicyPayloads", payloads);
        content.put("effectiveAt", effectiveAt.toString());
        content.put("expireAt", expireAt == null ? null : expireAt.toString());
        return Checksum.sha256Hex(CanonicalJson.write(content));
    }

    private SdkConfigEnvelopeRecord require(long id) {
        SdkConfigEnvelopeRecord envelope = mapper.selectEnvelope(id);
        if (envelope == null) throw new PlatformException(ErrorCode.NOT_FOUND, "SDK config envelope not found");
        return envelope;
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

    private SdkConfigActivationRecord activation(String id) {
        SdkConfigActivationRecord activation = mapper.selectActivation(required(id, "activationId", 64));
        if (activation == null) throw new PlatformException(ErrorCode.NOT_FOUND, "SDK config activation not found");
        return activation;
    }

    static List<Long> policyVersionIds(String refsJson) {
        try {
            JsonNode refs = new ObjectMapper().readTree(refsJson);
            if (!refs.isArray()) throw new IOException("refs is not an array");
            List<Long> ids = new ArrayList<>();
            refs.forEach(ref -> ids.add(ref.path("policyVersionId").longValue()));
            return List.copyOf(ids);
        } catch (IOException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored SDK policy refs are invalid", failure);
        }
    }

    private JsonNode parse(String json, String field) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored " + field + " is invalid", failure);
        }
    }

    private String json(JsonNode value) {
        return new String(CanonicalJson.write(value), StandardCharsets.UTF_8);
    }

    private String app(String value) {
        String normalized = required(value, "appCode", 128);
        if (!CODE.matcher(normalized).matches()) throw new PlatformException(ErrorCode.INVALID_REQUEST, "appCode is invalid");
        return normalized;
    }

    private String environment(String value) {
        return required(value, "environment", 32).toUpperCase(Locale.ROOT);
    }

    private String targetType(String value) {
        String normalized = required(value, "targetType", 16).toUpperCase(Locale.ROOT);
        if (!Set.of("APOLLO", "NACOS").contains(normalized)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "targetType must be APOLLO or NACOS");
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
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > maxLength) throw new PlatformException(ErrorCode.INVALID_REQUEST, "Value is too long");
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
            for (int index = 0; index < Math.max(before.size(), after.size()); index++) {
                compare(path + "/" + index, before.path(index), after.path(index), changes);
            }
            return;
        }
        if (!before.equals(after)) changes.add(new DiffEntry(display(path), "CHANGED", before, after));
    }

    private String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private String display(String path) { return path.isEmpty() ? "/" : path; }

    public record CreateCommand(
            String appCode, String environment, JsonNode routing, List<Long> securityPolicyVersionIds,
            Instant effectiveAt, Instant expireAt, String sourceAuditRef) {
    }

    public record PublishCommand(long expectedConfigVersion, String targetType, String targetNamespace) {
    }

    public record EventCommand(
            String activationId, String appCode, String environment, String sdkInstanceId,
            long envelopeId, Long oldConfigVersion, long newConfigVersion, JsonNode securityPolicyRefs,
            String status, Instant effectiveAt, String errorMasked, String sourceAuditRef) {
    }

    public record EnvelopeView(
            long id, String appCode, String environment, long configVersion, JsonNode routing,
            JsonNode securityPolicyRefs, JsonNode securityPolicyPayloads, Instant effectiveAt,
            Instant expireAt, String checksum, String signature, String signatureKeyId,
            String signatureAlgorithm,
            String validationStatus, String status, Long approvalRequestId, String sourceAuditRef,
            String createdBy, Instant createdAt, String publishedBy, Instant publishedAt) {
    }

    public record DiffEntry(String path, String changeType, JsonNode before, JsonNode after) {
    }
    public record EnvelopeDiff(long compareTo, long envelopeId, List<DiffEntry> changes) {
    }
    private record PreparedWrapper(byte[] bytes) {
    }
}
