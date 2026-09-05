package com.xuntian.mock.control.flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.provider.ProviderRecord;
import com.xuntian.mock.control.provider.ProviderService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class FlowDefinitionService {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private final FlowDefinitionMapper mapper;
    private final ProviderService providerService;
    private final FlowDefinitionValidator validator;
    private final ApprovalService approvalService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public FlowDefinitionService(
            FlowDefinitionMapper mapper,
            ProviderService providerService,
            FlowDefinitionValidator validator,
            ApprovalService approvalService,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.providerService = providerService;
        this.validator = validator;
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public List<FlowDefinitionSummary> findAll() {
        return mapper.selectAll().stream().map(this::summary).toList();
    }

    public FlowDefinitionView find(long id) {
        FlowDefinitionRecord definition = require(id);
        return new FlowDefinitionView(summary(definition), mapper.selectVersions(id).stream().map(this::versionView).toList());
    }

    @Transactional
    public FlowDefinitionSummary create(CreateCommand command, OperatorContext operator) {
        ProviderRecord provider = providerService.require(command.providerId());
        if (!"ENABLED".equals(provider.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Disabled Provider cannot receive a Flow Definition");
        }
        String code = code(command.flowCode());
        String name = required(command.flowName(), "flowName", 128);
        try {
            mapper.insertDefinition(provider.id(), code, name, operator.operatorId());
        } catch (DuplicateKeyException duplicate) {
            throw new PlatformException(ErrorCode.CONFLICT, "Flow Code already exists for this Provider", duplicate);
        }
        FlowDefinitionRecord created = mapper.selectByProviderAndCode(provider.id(), code);
        auditService.record(operator, "FLOW_DEFINITION_CREATE", "FLOW_DEFINITION", created.id(), null,
                null, auditDefinition(created));
        return summary(created);
    }

    @Transactional
    public FlowDefinitionSummary update(long id, UpdateCommand command, OperatorContext operator) {
        FlowDefinitionRecord before = require(id);
        String name = required(command.flowName(), "flowName", 128);
        String status = status(command.status());
        if (mapper.updateDefinition(id, name, status, operator.operatorId()) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Flow Definition changed concurrently");
        }
        FlowDefinitionRecord updated = require(id);
        auditService.record(operator, "FLOW_DEFINITION_UPDATE", "FLOW_DEFINITION", id, null,
                auditDefinition(before), auditDefinition(updated));
        return summary(updated);
    }

    @Transactional
    public FlowDefinitionVersionView createVersion(
            long definitionId,
            CreateVersionCommand command,
            OperatorContext operator) {
        FlowDefinitionRecord definition = mapper.lockById(definitionId);
        if (definition == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Flow Definition not found");
        if (!"ENABLED".equals(definition.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Disabled Flow Definition cannot receive a version");
        }
        String initialState = codeLike(command.initialState(), "initialState", 64);
        if (command.ttlSeconds() < 60 || command.ttlSeconds() > 2_592_000) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "ttlSeconds must be from 60 to 2592000");
        }
        JsonNode participants = requireArray(command.participantApis(), "participantApis");
        JsonNode variables = requireArray(command.variables(), "variables");
        JsonNode transitions = requireArray(command.transitions(), "transitions");
        int versionNo = mapper.nextVersionNo(definitionId);
        String checksum = checksum(definition, initialState, command.ttlSeconds(), participants, variables, transitions);
        try {
            mapper.insertVersion(
                    definitionId, versionNo, initialState, command.ttlSeconds(),
                    json(participants), json(variables), json(transitions), checksum, operator.operatorId());
        } catch (DuplicateKeyException duplicate) {
            throw new PlatformException(ErrorCode.CONFLICT, "An identical Flow Definition Version already exists", duplicate);
        }
        mapper.updateCurrentDraft(definitionId, versionNo, operator.operatorId());
        FlowDefinitionVersionRecord created = mapper.selectVersionByNumber(definitionId, versionNo);
        auditService.record(operator, "FLOW_DEFINITION_VERSION_CREATE", FlowDefinitionApprovalHandler.OBJECT_TYPE,
                created.id(), checksum, null, auditVersion(created));
        return versionView(created);
    }

    @Transactional
    public FlowDefinitionVersionView validate(long id, OperatorContext operator) {
        FlowDefinitionVersionRecord before = mapper.lockVersionById(id);
        if (before == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Flow Definition Version not found");
        if (!Set.of("DRAFT", "VALIDATED").contains(before.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Flow Definition Version is immutable in current status");
        }
        FlowDefinitionRecord definition = require(before.flowDefinitionId());
        assertStoredChecksum(definition, before);
        FlowDefinitionValidator.ValidationOutcome outcome = validator.validate(definition, before);
        String resultJson = json(objectMapper.valueToTree(Map.of(
                "valid", outcome.valid(), "errors", outcome.errors(), "warnings", outcome.warnings())));
        String compiledJson = outcome.valid() ? json(outcome.compiled()) : null;
        int updated = mapper.saveValidation(
                id, before.checksum(), outcome.valid() ? "VALIDATED" : "DRAFT",
                outcome.valid() ? "VALID" : "INVALID", resultJson, compiledJson);
        if (updated != 1) throw new PlatformException(ErrorCode.CONFLICT, "Flow Definition Version changed concurrently");
        FlowDefinitionVersionRecord after = requireVersion(id);
        auditService.record(operator, "FLOW_DEFINITION_VERSION_VALIDATE", FlowDefinitionApprovalHandler.OBJECT_TYPE,
                id, before.checksum(), auditVersion(before), auditVersion(after));
        return versionView(after);
    }

    @Transactional
    public ApprovalService.ApprovalView submitApproval(
            long id,
            String policyCode,
            int requiredCount,
            OperatorContext operator) {
        FlowDefinitionVersionRecord version = requireVersion(id);
        if (!"VALIDATED".equals(version.status()) || !"VALID".equals(version.validationStatus())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only a valid Flow Definition Version can be submitted");
        }
        assertStoredChecksum(require(version.flowDefinitionId()), version);
        return approvalService.submit(
                FlowDefinitionApprovalHandler.OBJECT_TYPE,
                id,
                version.checksum(),
                policyCode == null || policyCode.isBlank() ? "FLOW_DUAL_CONTROL" : policyCode,
                requiredCount == 0 ? 2 : requiredCount,
                operator,
                version.createdBy());
    }

    public FlowDefinitionRecord require(long id) {
        FlowDefinitionRecord definition = mapper.selectById(id);
        if (definition == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Flow Definition not found");
        return definition;
    }

    public FlowDefinitionVersionRecord requireVersion(long id) {
        FlowDefinitionVersionRecord version = mapper.selectVersionById(id);
        if (version == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Flow Definition Version not found");
        return version;
    }

    public void requireApprovedOrPublished(long id) {
        FlowDefinitionVersionRecord version = requireVersion(id);
        if (!Set.of("APPROVED", "PUBLISHED").contains(version.status())
                || !"VALID".equals(version.validationStatus()) || version.compiledJson() == null) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Flow Definition Version must be APPROVED or PUBLISHED");
        }
        assertStoredChecksum(require(version.flowDefinitionId()), version);
    }

    private void assertStoredChecksum(FlowDefinitionRecord definition, FlowDefinitionVersionRecord version) {
        String actual = checksum(
                definition, version.initialState(), version.ttlSeconds(),
                parse(version.participantApisJson()), parse(version.variablesJson()), parse(version.transitionsJson()));
        if (!actual.equals(version.checksum())) {
            throw new PlatformException(ErrorCode.CONFLICT, "Stored Flow Definition checksum does not match immutable content");
        }
    }

    private String checksum(
            FlowDefinitionRecord definition,
            String initialState,
            long ttlSeconds,
            JsonNode participants,
            JsonNode variables,
            JsonNode transitions) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("flowDefinitionId", definition.id());
        content.put("providerId", definition.providerId());
        content.put("flowCode", definition.flowCode());
        content.put("initialState", initialState);
        content.put("ttlSeconds", ttlSeconds);
        content.put("participantApis", participants);
        content.put("variables", variables);
        content.put("transitions", transitions);
        return Checksum.sha256Hex(CanonicalJson.write(content));
    }

    private FlowDefinitionSummary summary(FlowDefinitionRecord value) {
        return new FlowDefinitionSummary(
                value.id(), value.providerId(), value.flowCode(), value.flowName(),
                value.currentDraftVersion(), value.status(), value.createdBy(), value.createdAt(),
                value.updatedBy(), value.updatedAt());
    }

    private FlowDefinitionVersionView versionView(FlowDefinitionVersionRecord value) {
        return new FlowDefinitionVersionView(
                value.id(), value.flowDefinitionId(), value.versionNo(), value.status(), value.initialState(),
                value.ttlSeconds(), parse(value.participantApisJson()), parse(value.variablesJson()),
                parse(value.transitionsJson()), value.compiledJson() == null ? null : parse(value.compiledJson()),
                value.checksum(), value.validationStatus(),
                value.validationResultJson() == null ? null : parse(value.validationResultJson()),
                value.approvalRequestId(), value.approvedAt(), value.publishedAt(), value.createdBy(), value.createdAt());
    }

    private Map<String, Object> auditDefinition(FlowDefinitionRecord value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("providerId", value.providerId());
        result.put("flowCode", value.flowCode());
        result.put("flowName", value.flowName());
        result.put("status", value.status());
        return result;
    }

    private Map<String, Object> auditVersion(FlowDefinitionVersionRecord value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("flowDefinitionId", value.flowDefinitionId());
        result.put("versionNo", value.versionNo());
        result.put("status", value.status());
        result.put("checksum", value.checksum());
        result.put("validationStatus", value.validationStatus());
        return result;
    }

    private String code(String value) {
        if (value == null || !CODE.matcher(value.trim()).matches()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "flowCode is invalid");
        }
        return value.trim();
    }

    private String codeLike(String value, String field, int maxLength) {
        String normalized = required(value, field, maxLength);
        if (!normalized.matches("[A-Za-z0-9._-]+")) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return normalized;
    }

    private String status(String value) {
        String normalized = value == null ? "ENABLED" : value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) throw new PlatformException(ErrorCode.INVALID_REQUEST, "status is invalid");
        return normalized;
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return value.trim();
    }

    private JsonNode requireArray(JsonNode value, String field) {
        if (value == null || !value.isArray()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " must be an array");
        }
        if (CanonicalJson.write(value).length > 1024 * 1024) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " exceeds 1MB");
        }
        return value.deepCopy();
    }

    private String json(JsonNode value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Flow JSON is invalid", failure);
        }
    }

    private JsonNode parse(String value) {
        try {
            return value == null ? objectMapper.nullNode() : objectMapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Flow JSON is invalid", failure);
        }
    }

    public record CreateCommand(long providerId, String flowCode, String flowName) { }

    public record UpdateCommand(String flowName, String status) { }

    public record CreateVersionCommand(
            String initialState,
            long ttlSeconds,
            JsonNode participantApis,
            JsonNode variables,
            JsonNode transitions) { }

    public record FlowDefinitionSummary(
            long id,
            long providerId,
            String flowCode,
            String flowName,
            Integer currentDraftVersion,
            String status,
            String createdBy,
            java.time.Instant createdAt,
            String updatedBy,
            java.time.Instant updatedAt) { }

    public record FlowDefinitionVersionView(
            long id,
            long flowDefinitionId,
            int versionNo,
            String status,
            String initialState,
            long ttlSeconds,
            JsonNode participantApis,
            JsonNode variables,
            JsonNode transitions,
            JsonNode compiled,
            String checksum,
            String validationStatus,
            JsonNode validationResult,
            Long approvalRequestId,
            java.time.Instant approvedAt,
            java.time.Instant publishedAt,
            String createdBy,
            java.time.Instant createdAt) { }

    public record FlowDefinitionView(
            FlowDefinitionSummary flowDefinition,
            List<FlowDefinitionVersionView> versions) { }
}
