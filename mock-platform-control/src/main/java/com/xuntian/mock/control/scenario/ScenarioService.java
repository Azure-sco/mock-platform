package com.xuntian.mock.control.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.api.ApiRecord;
import com.xuntian.mock.control.api.ApiService;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.provider.ProviderService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ScenarioService {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> ROOT_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final String APPROVAL_POLICY = "SCENARIO_TEST_SINGLE";

    private final ScenarioMapper mapper;
    private final ProviderService providerService;
    private final ApiService apiService;
    private final ScenarioValidator validator;
    private final ApprovalService approvalService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ScenarioService(
            ScenarioMapper mapper,
            ProviderService providerService,
            ApiService apiService,
            ScenarioValidator validator,
            ApprovalService approvalService,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.providerService = providerService;
        this.apiService = apiService;
        this.validator = validator;
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public List<ScenarioSummary> findAll() {
        return mapper.selectAll().stream().map(this::summary).toList();
    }

    public ScenarioView find(long id) {
        ScenarioRecord scenario = require(id);
        return new ScenarioView(summary(scenario), mapper.selectVersions(id).stream().map(this::versionView).toList());
    }

    @Transactional
    public ScenarioSummary create(CreateCommand command, OperatorContext operator) {
        String code = code(command.scenarioCode());
        String name = required(command.scenarioName(), "scenarioName", 128);
        providerService.require(command.providerId());
        ApiRecord api = apiService.require(command.apiId());
        if (api.providerId() != command.providerId()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "API does not belong to provider");
        }
        try {
            mapper.insertScenario(code, name, command.providerId(), command.apiId(), operator.operatorId());
        } catch (DuplicateKeyException duplicate) {
            throw new PlatformException(ErrorCode.CONFLICT, "Scenario code already exists", duplicate);
        }
        ScenarioRecord created = mapper.selectByCode(code);
        auditService.record(operator, "SCENARIO_CREATE", "SCENARIO", created.id(), null, null, auditScenario(created));
        return summary(created);
    }

    @Transactional
    public ScenarioSummary update(long id, UpdateCommand command, OperatorContext operator) {
        ScenarioRecord before = require(id);
        String name = required(command.scenarioName(), "scenarioName", 128);
        String status = rootStatus(command.status());
        if (mapper.updateScenario(id, name, status, operator.operatorId()) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Scenario changed concurrently");
        }
        ScenarioRecord updated = require(id);
        auditService.record(operator, "SCENARIO_UPDATE", "SCENARIO", id, null,
                auditScenario(before), auditScenario(updated));
        return summary(updated);
    }

    @Transactional
    public ScenarioSummary disable(long id, OperatorContext operator) {
        ScenarioRecord before = require(id);
        if ("DISABLED".equals(before.status())) return summary(before);
        if (mapper.disableScenario(id, operator.operatorId()) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Scenario changed concurrently");
        }
        ScenarioRecord updated = require(id);
        auditService.record(operator, "SCENARIO_DISABLE", "SCENARIO", id, null,
                auditScenario(before), auditScenario(updated));
        return summary(updated);
    }

    @Transactional
    public ScenarioVersionView createVersion(
            long scenarioId,
            CreateVersionCommand command,
            OperatorContext operator) {
        ScenarioRecord scenario = mapper.lockById(scenarioId);
        if (scenario == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Scenario not found");
        }
        if (!"ENABLED".equals(scenario.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Disabled Scenario cannot receive a new version");
        }
        validateVersionInput(command);
        int versionNo = mapper.nextVersionNo(scenarioId);
        String scopeJson = json(command.scope(), "scope");
        String ruleJson = json(command.matchRules(), "matchRules");
        String responseJson = json(command.response(), "response");
        JsonNode callbacks = command.callbacks() == null ? objectMapper.createArrayNode() : command.callbacks();
        String callbackJson = json(callbacks, "callbacks");
        String checksum = checksum(command, callbacks);
        try {
            mapper.insertVersion(
                    scenarioId, versionNo, command.contractVersionId(), command.flowDefinitionVersionId(),
                    command.priority(), command.effectiveFrom(), command.effectiveTo(),
                    scopeJson, ruleJson, responseJson, callbackJson, checksum, operator.operatorId());
        } catch (DuplicateKeyException duplicate) {
            throw new PlatformException(ErrorCode.CONFLICT,
                    "An identical Scenario version already exists for this Scenario", duplicate);
        }
        mapper.updateCurrentDraft(scenarioId, versionNo, operator.operatorId());
        ScenarioVersionRecord created = mapper.selectVersionByNumber(scenarioId, versionNo);
        auditService.record(operator, "SCENARIO_VERSION_CREATE", "SCENARIO_VERSION", created.id(), checksum,
                null, auditVersion(created));
        return versionView(created);
    }

    @Transactional
    public ScenarioVersionView validate(long id, OperatorContext operator) {
        ScenarioVersionRecord before = mapper.lockVersionById(id);
        if (before == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Scenario version not found");
        }
        if (!Set.of("DRAFT", "VALIDATED").contains(before.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Scenario version cannot be validated in current status");
        }
        assertStoredChecksum(before);
        ScenarioRecord scenario = require(before.scenarioId());
        ScenarioValidator.ValidationOutcome outcome = validator.validate(scenario, before, operator);
        String validationStatus = outcome.valid() ? "VALID" : "INVALID";
        String status = outcome.valid() ? "VALIDATED" : "DRAFT";
        String resultJson = json(objectMapper.valueToTree(Map.of(
                "valid", outcome.valid(), "errors", outcome.errors(), "warnings", outcome.warnings())),
                "validationResult");
        String compiledJson = outcome.valid() ? json(outcome.compiled(), "compiled") : null;
        if (mapper.saveValidation(id, before.checksum(), status, validationStatus, resultJson, compiledJson) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Scenario version changed concurrently");
        }
        ScenarioVersionRecord updated = requireVersion(id);
        auditService.record(operator, "SCENARIO_VERSION_VALIDATE", "SCENARIO_VERSION", id, before.checksum(),
                auditVersion(before), auditVersion(updated));
        return versionView(updated);
    }

    @Transactional
    public ApprovalService.ApprovalView submitApproval(long id, OperatorContext operator) {
        ScenarioVersionRecord version = requireVersion(id);
        if (!"VALIDATED".equals(version.status()) || !"VALID".equals(version.validationStatus())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only a valid Scenario version can be submitted");
        }
        assertStoredChecksum(version);
        return approvalService.submit(
                ScenarioApprovalHandler.OBJECT_TYPE,
                id,
                version.checksum(),
                APPROVAL_POLICY,
                1,
                operator,
                version.createdBy());
    }

    @Transactional
    public ScenarioView copy(long sourceVersionId, CopyCommand command, OperatorContext operator) {
        ScenarioVersionRecord source = requireVersion(sourceVersionId);
        ScenarioRecord sourceRoot = require(source.scenarioId());
        ScenarioSummary target = create(
                new CreateCommand(
                        command.scenarioCode(), command.scenarioName(), sourceRoot.providerId(), sourceRoot.apiId()),
                operator);
        createVersion(target.id(), new CreateVersionCommand(
                source.contractVersionId(), source.flowDefinitionVersionId(), source.priority(),
                source.effectiveFrom(), source.effectiveTo(), parse(source.scopeJson()),
                parse(source.matchRuleJson()), parse(source.responseJson()), parse(source.callbackJson())), operator);
        return find(target.id());
    }

    public ScenarioRecord require(long id) {
        ScenarioRecord scenario = mapper.selectById(id);
        if (scenario == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Scenario not found");
        return scenario;
    }

    public ScenarioVersionRecord requireVersion(long id) {
        ScenarioVersionRecord version = mapper.selectVersionById(id);
        if (version == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Scenario version not found");
        return version;
    }

    private void validateVersionInput(CreateVersionCommand command) {
        if (command.contractVersionId() <= 0) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "contractVersionId is invalid");
        }
        if (command.priority() < -100_000 || command.priority() > 100_000) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "priority is outside the supported range");
        }
        if (command.effectiveFrom() != null && command.effectiveTo() != null
                && !command.effectiveTo().isAfter(command.effectiveFrom())) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "effectiveTo must be after effectiveFrom");
        }
        if (command.scope() == null || !command.scope().isObject()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "scope must be an object");
        }
        if (command.matchRules() == null || !command.matchRules().isArray()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "matchRules must be an array");
        }
        if (command.response() == null || !command.response().isObject()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "response must be an object");
        }
        if (command.callbacks() != null && !command.callbacks().isArray()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "callbacks must be an array");
        }
    }

    private String checksum(CreateVersionCommand command, JsonNode callbacks) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("contractVersionId", command.contractVersionId());
        content.put("flowDefinitionVersionId", command.flowDefinitionVersionId());
        content.put("priority", command.priority());
        content.put("effectiveFrom", command.effectiveFrom() == null ? null : command.effectiveFrom().toString());
        content.put("effectiveTo", command.effectiveTo() == null ? null : command.effectiveTo().toString());
        content.put("scope", canonicalValue(command.scope()));
        content.put("matchRules", canonicalValue(command.matchRules()));
        content.put("response", canonicalValue(command.response()));
        content.put("callbacks", canonicalValue(callbacks));
        return Checksum.sha256Hex(CanonicalJson.write(content));
    }

    private void assertStoredChecksum(ScenarioVersionRecord version) {
        CreateVersionCommand content = new CreateVersionCommand(
                version.contractVersionId(), version.flowDefinitionVersionId(), version.priority(),
                version.effectiveFrom(), version.effectiveTo(), parse(version.scopeJson()),
                parse(version.matchRuleJson()), parse(version.responseJson()), parse(version.callbackJson()));
        String actual = checksum(content, content.callbacks());
        if (!actual.equals(version.checksum())) {
            throw new PlatformException(ErrorCode.CONFLICT, "Stored Scenario checksum does not match immutable content");
        }
    }

    private Object canonicalValue(JsonNode value) {
        return value == null || value.isNull() ? null : objectMapper.convertValue(value, Object.class);
    }

    private String json(JsonNode value, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " JSON is invalid", failure);
        }
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Scenario JSON is invalid", failure);
        }
    }

    private String code(String value) {
        if (value == null || !CODE.matcher(value.trim()).matches()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "scenarioCode is invalid");
        }
        return value.trim();
    }

    private String rootStatus(String value) {
        String normalized = value == null ? "ENABLED" : value.trim().toUpperCase(Locale.ROOT);
        if (!ROOT_STATUSES.contains(normalized)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "status must be ENABLED or DISABLED");
        }
        return normalized;
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return value.trim();
    }

    private ScenarioSummary summary(ScenarioRecord scenario) {
        return new ScenarioSummary(
                scenario.id(), scenario.scenarioCode(), scenario.scenarioName(),
                scenario.providerId(), scenario.apiId(), scenario.currentDraftVersion(), scenario.status(),
                scenario.createdBy(), scenario.createdAt(), scenario.updatedBy(), scenario.updatedAt());
    }

    private ScenarioVersionView versionView(ScenarioVersionRecord version) {
        return new ScenarioVersionView(
                version.id(), version.scenarioId(), version.versionNo(), version.status(),
                version.contractVersionId(), version.flowDefinitionVersionId(), version.priority(),
                version.effectiveFrom(), version.effectiveTo(), parse(version.scopeJson()),
                parse(version.matchRuleJson()), parse(version.responseJson()), parse(version.callbackJson()),
                version.compiledJson() == null ? null : parse(version.compiledJson()), version.checksum(),
                version.validationStatus(), version.validationResultJson() == null ? null : parse(version.validationResultJson()),
                version.approvalRequestId(), version.approvedAt(), version.publishedAt(), version.disabledAt(),
                version.createdBy(), version.createdAt());
    }

    private Map<String, Object> auditScenario(ScenarioRecord scenario) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", scenario.id());
        value.put("scenarioCode", scenario.scenarioCode());
        value.put("providerId", scenario.providerId());
        value.put("apiId", scenario.apiId());
        value.put("status", scenario.status());
        return value;
    }

    private Map<String, Object> auditVersion(ScenarioVersionRecord version) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", version.id());
        value.put("scenarioId", version.scenarioId());
        value.put("versionNo", version.versionNo());
        value.put("status", version.status());
        value.put("validationStatus", version.validationStatus());
        value.put("checksum", version.checksum());
        return value;
    }

    public record CreateCommand(String scenarioCode, String scenarioName, long providerId, long apiId) {
    }

    public record UpdateCommand(String scenarioName, String status) {
    }

    public record CreateVersionCommand(
            long contractVersionId,
            Long flowDefinitionVersionId,
            int priority,
            Instant effectiveFrom,
            Instant effectiveTo,
            JsonNode scope,
            JsonNode matchRules,
            JsonNode response,
            JsonNode callbacks) {
    }

    public record CopyCommand(String scenarioCode, String scenarioName) {
    }

    public record ScenarioSummary(
            long id,
            String scenarioCode,
            String scenarioName,
            long providerId,
            long apiId,
            Integer currentDraftVersion,
            String status,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
    }

    public record ScenarioView(ScenarioSummary scenario, List<ScenarioVersionView> versions) {
    }

    public record ScenarioVersionView(
            long id,
            long scenarioId,
            int versionNo,
            String status,
            long contractVersionId,
            Long flowDefinitionVersionId,
            int priority,
            Instant effectiveFrom,
            Instant effectiveTo,
            JsonNode scope,
            JsonNode matchRules,
            JsonNode response,
            JsonNode callbacks,
            JsonNode compiled,
            String checksum,
            String validationStatus,
            JsonNode validationResult,
            Long approvalRequestId,
            Instant approvedAt,
            Instant publishedAt,
            Instant disabledAt,
            String createdBy,
            Instant createdAt) {
    }
}
