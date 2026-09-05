package com.xuntian.mock.control.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Component
public final class ReleaseSnapshotCompiler {

    public static final String SCHEMA_VERSION = "2";
    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private final ReleaseMapper mapper;
    private final ObjectMapper objectMapper;
    private final CanonicalJsonCodec canonicalJson;
    private final RuntimeSnapshotSigner signer;
    private final ReleaseSecurityPolicyGate securityPolicyGate;

    public ReleaseSnapshotCompiler(
            ReleaseMapper mapper,
            ObjectMapper objectMapper,
            CanonicalJsonCodec canonicalJson,
            RuntimeSnapshotSigner signer,
            ReleaseSecurityPolicyGate securityPolicyGate) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.canonicalJson = canonicalJson;
        this.signer = signer;
        this.securityPolicyGate = securityPolicyGate;
    }

    public Selection validateSelection(String environment, String app, List<Long> requestedIds) {
        String normalizedEnvironment = environment(environment);
        String normalizedApp = app(app);
        if (requestedIds == null || requestedIds.isEmpty() || requestedIds.size() > 1000) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "scenarioVersionIds must contain 1 to 1000 values");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>(requestedIds);
        if (unique.size() != requestedIds.size() || unique.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "scenarioVersionIds must be unique positive IDs");
        }
        securityPolicyGate.requirePublishedAndBound(normalizedEnvironment, normalizedApp);
        List<ReleaseSourceRecord> sources = mapper.selectReleaseSources(List.copyOf(unique));
        if (sources.size() != unique.size()) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "One or more Scenario versions were not found");
        }
        Map<String, Long> contractByApi = new LinkedHashMap<>();
        Map<String, Integer> scenariosByApi = new LinkedHashMap<>();
        for (ReleaseSourceRecord source : sources) {
            if (!Set.of("APPROVED", "PUBLISHED").contains(source.scenarioStatus())) {
                throw new PlatformException(ErrorCode.INVALID_STATE,
                        "Scenario version must be APPROVED or PUBLISHED: " + source.scenarioVersionId());
            }
            if (!"VALID".equals(source.validationStatus()) || source.compiledJson() == null) {
                throw new PlatformException(ErrorCode.INVALID_STATE,
                        "Scenario version has no valid compiled artifact: " + source.scenarioVersionId());
            }
            verifyScenarioChecksum(source);
            if (!"ENABLED".equals(source.scenarioRootStatus())
                    || !"ENABLED".equals(source.providerStatus())
                    || !"ENABLED".equals(source.apiStatus())) {
                throw new PlatformException(ErrorCode.INVALID_STATE,
                        "Scenario, Provider and API must be enabled: " + source.scenarioVersionId());
            }
            if (!"PUBLISHED".equals(source.contractStatus())) {
                throw new PlatformException(ErrorCode.INVALID_STATE,
                        "Referenced Contract must be PUBLISHED: " + source.contractVersionId());
            }
            validateFlowSource(source);
            JsonNode callbacks = parse(source.callbackJson(), "callbacks");
            if (!callbacks.isArray()) {
                throw new PlatformException(ErrorCode.INVALID_STATE, "Stored Callback bindings are invalid");
            }
            Scope sourceScope = scope(parse(source.scopeJson(), "scope"));
            if (!sourceScope.environments().contains(normalizedEnvironment)
                    || !sourceScope.apps().contains(normalizedApp)) {
                throw new PlatformException(ErrorCode.INVALID_REQUEST,
                        "Scenario scope does not include Release environment/app: " + source.scenarioVersionId());
            }
            String apiKey = source.providerCode() + "\u0000" + source.apiCode();
            Long previousContract = contractByApi.putIfAbsent(apiKey, source.contractVersionId());
            if (previousContract != null && previousContract.longValue() != source.contractVersionId()) {
                throw new PlatformException(ErrorCode.CONFLICT,
                        "Selected Scenarios use different Contract versions for API " + source.apiCode());
            }
            int count = scenariosByApi.merge(apiKey, 1, Integer::sum);
            if (count > 100) {
                throw new PlatformException(ErrorCode.INVALID_REQUEST,
                        "An API cannot have more than 100 Scenarios in one Release");
            }
        }
        detectSelectedConflicts(sources);
        return new Selection(normalizedEnvironment, normalizedApp, List.copyOf(sources));
    }

    public CompiledRelease compile(
            String releaseId,
            Instant createdAt,
            Selection selection) {
        List<ContractDefinition> contracts = compileContracts(selection.sources());
        List<ScenarioDefinition> scenarios = selection.sources().stream()
                .map(source -> scenario(source, selection.environment(), selection.app()))
                .toList();
        List<FlowDefinition> flows = compileFlows(selection.sources());
        PublishedSnapshot snapshot = new PublishedSnapshot(
                SCHEMA_VERSION,
                releaseId,
                selection.environment(),
                selection.app(),
                createdAt,
                new CompiledArtifactManifest("contract-v1", "matcher-v1", "template-v1", "flow-v1"),
                contracts,
                scenarios,
                flows);
        byte[] snapshotBytes = canonicalJson.write(snapshot);
        String checksum = Checksum.sha256Hex(snapshotBytes);
        RuntimeSnapshotSigner.SignatureValue signature = signer.sign(snapshotBytes);
        if (!SIGNATURE_ALGORITHM.equals(signature.algorithm())) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                    "Runtime Snapshot signer returned an unsupported algorithm");
        }
        SignedEnvelope envelope = new SignedEnvelope(
                snapshot,
                checksum,
                Base64.getEncoder().encodeToString(signature.signature()),
                signature.keyId(),
                signature.algorithm());
        byte[] envelopeBytes = canonicalJson.write(envelope);
        verifyEnvelope(envelopeBytes, releaseId, checksum);
        return new CompiledRelease(
                snapshot, snapshotBytes, envelope, envelopeBytes, checksum,
                signature.signature(), signature.keyId(), signature.algorithm(), selection.sources());
    }

    public void verifyEnvelope(byte[] envelopeBytes, String expectedReleaseId, String expectedChecksum) {
        JsonNode envelope = canonicalJson.read(envelopeBytes);
        JsonNode snapshot = envelope.path("snapshot");
        if (!snapshot.isObject() || !expectedReleaseId.equals(snapshot.path("releaseId").asText())) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Snapshot envelope Release ID is invalid");
        }
        byte[] canonicalSnapshot = canonicalJson.write(snapshot);
        String checksum = Checksum.sha256Hex(canonicalSnapshot);
        if (!expectedChecksum.equals(checksum) || !checksum.equals(envelope.path("checksum").asText())) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Snapshot checksum verification failed");
        }
        String algorithm = envelope.path("signatureAlgorithm").asText();
        String keyId = envelope.path("signatureKeyId").asText();
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(envelope.path("signature").asText());
        } catch (IllegalArgumentException invalid) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Snapshot signature encoding is invalid", invalid);
        }
        signer.verify(canonicalSnapshot, signature, keyId, algorithm);
    }

    private List<ContractDefinition> compileContracts(List<ReleaseSourceRecord> sources) {
        Map<Long, ContractDefinition> contracts = new LinkedHashMap<>();
        for (ReleaseSourceRecord source : sources) {
            contracts.putIfAbsent(source.contractVersionId(), new ContractDefinition(
                    source.contractVersionId(),
                    source.providerCode(), source.apiCode(), source.httpMethod(), source.path(),
                    contentTypes(source.contentType()),
                    parse(source.requestSchemaJson(), "request schema"),
                    parse(source.responseSchemaJson(), "response schema"),
                    businessKey(source.businessKeyExtractorJson())));
        }
        return contracts.values().stream()
                .sorted(Comparator.comparing(ContractDefinition::provider).thenComparing(ContractDefinition::api))
                .toList();
    }

    private ScenarioDefinition scenario(ReleaseSourceRecord source, String environment, String app) {
        JsonNode rawScope = parse(source.scopeJson(), "Scenario scope");
        Scope sourceScope = scope(rawScope);
        ScopeDefinition scope = new ScopeDefinition(
                List.of(environment), List.of(app), sourceScope.tenants(), sourceScope.testAccounts());
        List<RuleDefinition> rules = new ArrayList<>();
        for (JsonNode rule : parse(source.matchRuleJson(), "Scenario match rules")) {
            rules.add(new RuleDefinition(
                    rule.path("type").asText(), rule.path("operator").asText(), rule.path("key").asText(),
                    rule.hasNonNull("value") ? rule.path("value").asText() : null,
                    rule.path("caseSensitive").asBoolean(false)));
        }
        JsonNode response = parse(source.responseJson(), "Scenario response");
        Map<String, String> headers = stringMap(response.path("headers"), "headers");
        Map<String, String> defaults = stringMap(response.path("variableDefaults"), "variableDefaults");
        ResponseDefinition compiledResponse = new ResponseDefinition(
                response.path("httpStatus").asInt(), headers,
                response.path("bodyTemplate").asText(), defaults,
                response.path("delayMs").asLong(0),
                new FaultDefinition(
                        response.path("fault").path("type").asText("NONE"),
                        response.path("fault").path("durationMs").asLong(0),
                        response.path("fault").path("sideEffectPolicy").asText("APPLY_BEFORE_FAULT")));
        return new ScenarioDefinition(
                String.valueOf(source.scenarioId()), String.valueOf(source.scenarioVersionId()),
                source.scenarioCode(), source.providerCode(), source.apiCode(), source.priority(),
                source.effectiveFrom(), source.effectiveTo(), scope, List.copyOf(rules), compiledResponse,
                source.flowDefinitionVersionId() == null ? null : String.valueOf(source.flowDefinitionVersionId()),
                source.flowChecksum(), callbacks(source.callbackJson()));
    }

    private void validateFlowSource(ReleaseSourceRecord source) {
        JsonNode callbacks = parse(source.callbackJson(), "Scenario callbacks");
        if (source.flowDefinitionVersionId() == null) {
            if (callbacks != null && callbacks.isArray() && !callbacks.isEmpty()) {
                throw new PlatformException(ErrorCode.INVALID_STATE,
                        "Callback binding requires a Flow Definition Version: " + source.scenarioVersionId());
            }
            return;
        }
        if (!Set.of("APPROVED", "PUBLISHED").contains(source.flowVersionStatus())
                || !"VALID".equals(source.flowValidationStatus()) || source.flowCompiledJson() == null
                || source.flowChecksum() == null || source.flowDefinitionId() == null
                || source.flowProviderId() == null || source.flowProviderId() != source.providerId()
                || source.flowVersionNo() == null || source.flowVersionNo() < 1
                || source.flowTtlSeconds() == null || source.flowInitialState() == null) {
            throw new PlatformException(ErrorCode.INVALID_STATE,
                    "Referenced Flow Definition Version is not valid and approved: " + source.flowDefinitionVersionId());
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("flowDefinitionId", source.flowDefinitionId());
        content.put("providerId", source.flowProviderId());
        content.put("flowCode", source.flowCode());
        content.put("initialState", source.flowInitialState());
        content.put("ttlSeconds", source.flowTtlSeconds());
        JsonNode participants = parse(source.flowParticipantApisJson(), "Flow participants");
        JsonNode variables = parse(source.flowVariablesJson(), "Flow variables");
        JsonNode transitions = parse(source.flowTransitionsJson(), "Flow transitions");
        content.put("participantApis", objectMapper.convertValue(participants, Object.class));
        content.put("variables", objectMapper.convertValue(variables, Object.class));
        content.put("transitions", objectMapper.convertValue(transitions, Object.class));
        if (!source.flowChecksum().equals(Checksum.sha256Hex(CanonicalJson.write(content)))) {
            throw new PlatformException(ErrorCode.CONFLICT,
                    "Flow immutable content no longer matches approved checksum: " + source.flowDefinitionVersionId());
        }
        boolean participant = false;
        if (participants != null && participants.isArray()) {
            for (JsonNode value : participants) {
                if (source.apiCode().equals(value.path("apiCode").asText())
                        && source.contractVersionId() == value.path("contractVersionId").asLong(-1)) {
                    participant = true;
                    break;
                }
            }
        }
        if (!participant) {
            throw new PlatformException(ErrorCode.INVALID_STATE,
                    "Scenario API/Contract is not a Participant of its fixed Flow Version");
        }
    }

    private List<FlowDefinition> compileFlows(List<ReleaseSourceRecord> sources) {
        Map<Long, FlowDefinition> flows = new LinkedHashMap<>();
        for (ReleaseSourceRecord source : sources) {
            if (source.flowDefinitionVersionId() == null) continue;
            FlowDefinition compiled = flowDefinition(source);
            FlowDefinition previous = flows.putIfAbsent(source.flowDefinitionVersionId(), compiled);
            if (previous != null && !previous.equals(compiled)) {
                throw new PlatformException(ErrorCode.CONFLICT,
                        "Selected Scenarios disagree on one Flow Definition Version");
            }
        }
        return flows.values().stream()
                .sorted(Comparator.comparing(FlowDefinition::provider).thenComparing(FlowDefinition::flowCode))
                .toList();
    }

    private FlowDefinition flowDefinition(ReleaseSourceRecord source) {
        List<FlowParticipantDefinition> participants = new ArrayList<>();
        for (JsonNode value : parse(source.flowParticipantApisJson(), "Flow participants")) {
            participants.add(new FlowParticipantDefinition(
                    value.path("apiCode").asText(), value.path("contractVersionId").asLong(),
                    value.path("role").asText(), value.path("createIfAbsent").asBoolean(false),
                    businessKey(value.path("businessKeyExtractor"))));
        }
        List<FlowVariableDefinition> variables = new ArrayList<>();
        for (JsonNode value : parse(source.flowVariablesJson(), "Flow variables")) {
            JsonNode initial = value.path("initialValue");
            variables.add(new FlowVariableDefinition(
                    value.path("name").asText(), value.path("type").asText(),
                    value.path("required").asBoolean(false),
                    value.hasNonNull("maxLength") ? value.path("maxLength").asInt() : null,
                    initial.isObject() ? new FlowVariableInitialValueDefinition(
                            initial.path("source").asText(), nullableText(initial, "path"),
                            initial.has("value") ? initial.path("value") : null) : null));
        }
        List<FlowTransitionDefinition> transitions = new ArrayList<>();
        for (JsonNode value : parse(source.flowTransitionsJson(), "Flow transitions")) {
            JsonNode trigger = value.path("trigger");
            List<FlowAssignmentDefinition> assignments = new ArrayList<>();
            if (value.path("assignments").isArray()) {
                for (JsonNode assignment : value.path("assignments")) {
                    assignments.add(new FlowAssignmentDefinition(
                            assignment.path("type").asText(), assignment.path("variable").asText(),
                            nullableText(assignment, "source"), nullableText(assignment, "path"),
                            assignment.has("value") ? assignment.path("value") : null,
                            assignment.hasNonNull("increment") ? assignment.path("increment").numberValue() : null));
                }
            }
            transitions.add(new FlowTransitionDefinition(
                    value.path("transitionId").asText(), value.path("priority").asInt(),
                    value.path("from").asText(), value.path("to").asText(),
                    new FlowTriggerDefinition(
                            trigger.path("type").asText(),
                            trigger.hasNonNull("threshold") ? trigger.path("threshold").asLong() : null,
                            trigger.hasNonNull("delaySeconds") ? trigger.path("delaySeconds").asLong() : null,
                            nullableText(trigger, "source"), nullableText(trigger, "path"),
                            nullableText(trigger, "operator"),
                            trigger.has("value") ? trigger.path("value") : null),
                    List.copyOf(assignments)));
        }
        return new FlowDefinition(
                String.valueOf(source.flowDefinitionId()), String.valueOf(source.flowDefinitionVersionId()),
                source.providerCode(), source.flowCode(), source.flowVersionNo(), source.flowChecksum(),
                source.flowInitialState(), source.flowTtlSeconds(), List.copyOf(participants),
                List.copyOf(variables), List.copyOf(transitions));
    }

    private List<CallbackDefinition> callbacks(String json) {
        JsonNode values = parse(json, "Scenario callbacks");
        if (values == null || !values.isArray()) return List.of();
        List<CallbackDefinition> callbacks = new ArrayList<>();
        for (JsonNode value : values) {
            callbacks.add(new CallbackDefinition(
                    value.path("callbackDefinitionId").asText(), value.path("enabled").asBoolean(true),
                    value.path("triggerState").asText(), value.path("urlSource").asText(),
                    nullableText(value, "url"), nullableText(value, "requestField"),
                    value.path("method").asText(), stringMap(value.path("headers"), "Callback headers"),
                    value.path("payloadTemplate").asText(), value.path("delayMs").asLong(),
                    value.path("maxRetry").asInt(), longs(value.path("retryIntervalsMs")),
                    value.path("totalDeliveryCount").asInt(), longs(value.path("deliveryOffsetsMs")),
                    value.path("signaturePolicyVersionId").asLong(),
                    value.path("allowlistPolicyVersionId").asLong()));
        }
        return List.copyOf(callbacks);
    }

    private List<Long> longs(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<Long> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asLong()));
        return List.copyOf(result);
    }

    private BusinessKeyDefinition businessKey(String json) {
        if (json == null) return null;
        JsonNode value = parse(json, "businessKeyExtractor");
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Stored Business Key Extractor is invalid");
        }
        return new BusinessKeyDefinition(
                nullableText(value, "source"), nullableText(value, "path"),
                value.path("required").asBoolean(false), nullableText(value, "normalize"));
    }

    private BusinessKeyDefinition businessKey(JsonNode value) {
        if (value == null || !value.isObject()) return null;
        return new BusinessKeyDefinition(
                nullableText(value, "source"), nullableText(value, "path"),
                value.path("required").asBoolean(false), nullableText(value, "normalize"));
    }

    private List<String> contentTypes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    private Map<String, String> stringMap(JsonNode value, String field) {
        if (!value.isObject()) return Map.of();
        Map<String, String> result = new TreeMap<>();
        value.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isValueNode()) {
                throw new PlatformException(ErrorCode.INVALID_STATE, "Compiled " + field + " value must be scalar");
            }
            result.put(entry.getKey(), entry.getValue().asText());
        });
        return Map.copyOf(result);
    }

    private Scope scope(JsonNode value) {
        if (!value.isObject()) throw new PlatformException(ErrorCode.INVALID_STATE, "Scenario scope is invalid");
        List<String> environments = strings(
                value.has("environments") ? value.path("environments") : single(value.path("environment")));
        return new Scope(environments, strings(value.path("apps")), strings(value.path("tenants")),
                strings(value.path("testAccounts")));
    }

    private JsonNode single(JsonNode value) {
        return objectMapper.createArrayNode().add(value);
    }

    private List<String> strings(JsonNode value) {
        if (!value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(item -> { if (item.isTextual()) result.add(item.asText()); });
        return List.copyOf(result);
    }

    private void detectSelectedConflicts(List<ReleaseSourceRecord> sources) {
        for (int left = 0; left < sources.size(); left++) {
            ReleaseSourceRecord first = sources.get(left);
            for (int right = left + 1; right < sources.size(); right++) {
                ReleaseSourceRecord second = sources.get(right);
                if (!first.providerCode().equals(second.providerCode())
                        || !first.apiCode().equals(second.apiCode())
                        || first.priority() != second.priority()
                        || !timeOverlaps(first, second)) continue;
                if (parse(first.matchRuleJson(), "match rules").equals(parse(second.matchRuleJson(), "match rules"))) {
                    throw new PlatformException(ErrorCode.CONFLICT,
                            "Selected Scenario versions have an ambiguous same-priority match: "
                                    + first.scenarioVersionId() + " and " + second.scenarioVersionId());
                }
            }
        }
    }

    private void verifyScenarioChecksum(ReleaseSourceRecord source) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("contractVersionId", source.contractVersionId());
        content.put("flowDefinitionVersionId", source.flowDefinitionVersionId());
        content.put("priority", source.priority());
        content.put("effectiveFrom", source.effectiveFrom() == null ? null : source.effectiveFrom().toString());
        content.put("effectiveTo", source.effectiveTo() == null ? null : source.effectiveTo().toString());
        content.put("scope", objectMapper.convertValue(parse(source.scopeJson(), "Scenario scope"), Object.class));
        content.put("matchRules", objectMapper.convertValue(parse(source.matchRuleJson(), "Scenario match rules"), Object.class));
        content.put("response", objectMapper.convertValue(parse(source.responseJson(), "Scenario response"), Object.class));
        content.put("callbacks", objectMapper.convertValue(parse(source.callbackJson(), "Scenario callbacks"), Object.class));
        String actual = Checksum.sha256Hex(CanonicalJson.write(content));
        if (!actual.equals(source.scenarioChecksum())) {
            throw new PlatformException(ErrorCode.CONFLICT,
                    "Scenario immutable content no longer matches approved checksum: " + source.scenarioVersionId());
        }
    }

    private boolean timeOverlaps(ReleaseSourceRecord first, ReleaseSourceRecord second) {
        return (first.effectiveTo() == null || second.effectiveFrom() == null
                || first.effectiveTo().isAfter(second.effectiveFrom()))
                && (second.effectiveTo() == null || first.effectiveFrom() == null
                || second.effectiveTo().isAfter(first.effectiveFrom()));
    }

    private JsonNode parse(String value, String field) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored " + field + " JSON is invalid", failure);
        }
    }

    private String nullableText(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private String environment(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 32) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "environment is invalid");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("PROD".equals(normalized)) {
            throw new PlatformException(ErrorCode.FORBIDDEN, "PROD Release is forbidden");
        }
        return normalized;
    }

    private String app(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 128
                || !value.trim().matches("[A-Za-z0-9._-]+")) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "app is invalid");
        }
        return value.trim();
    }

    public record Selection(String environment, String app, List<ReleaseSourceRecord> sources) {
    }

    public record CompiledRelease(
            PublishedSnapshot snapshot,
            byte[] canonicalSnapshotBytes,
            SignedEnvelope envelope,
            byte[] canonicalEnvelopeBytes,
            String checksum,
            byte[] signature,
            String signatureKeyId,
            String signatureAlgorithm,
            List<ReleaseSourceRecord> sources) {
    }

    public record PublishedSnapshot(
            String schemaVersion,
            String releaseId,
            String environment,
            String app,
            Instant createdAt,
            CompiledArtifactManifest compiledArtifacts,
            List<ContractDefinition> compiledContracts,
            List<ScenarioDefinition> compiledScenarios,
            List<FlowDefinition> flowDefinitions) {
    }

    public record CompiledArtifactManifest(
            String contractCompilerVersion,
            String matcherCompilerVersion,
            String templateCompilerVersion,
            String flowCompilerVersion) {
    }

    public record ContractDefinition(
            long contractVersionId,
            String provider,
            String api,
            String method,
            String path,
            List<String> contentTypes,
            JsonNode requestSchema,
            JsonNode responseSchema,
            BusinessKeyDefinition businessKeyExtractor) {
    }

    public record BusinessKeyDefinition(String source, String path, boolean required, String normalize) {
    }

    public record ScenarioDefinition(
            String scenarioId,
            String scenarioVersionId,
            String scenarioCode,
            String provider,
            String api,
            int priority,
            Instant effectiveFrom,
            Instant effectiveTo,
            ScopeDefinition scope,
            List<RuleDefinition> matchRules,
            ResponseDefinition response,
            String flowDefinitionVersionId,
            String flowDefinitionChecksum,
            List<CallbackDefinition> callbacks) {
    }

    public record FlowDefinition(
            String flowDefinitionId,
            String flowDefinitionVersionId,
            String provider,
            String flowCode,
            long version,
            String checksum,
            String initialState,
            long ttlSeconds,
            List<FlowParticipantDefinition> participantApis,
            List<FlowVariableDefinition> variables,
            List<FlowTransitionDefinition> transitions) {
    }

    public record FlowParticipantDefinition(
            String apiCode,
            long contractVersionId,
            String role,
            boolean createIfAbsent,
            BusinessKeyDefinition businessKeyExtractor) {
    }

    public record FlowVariableDefinition(
            String name,
            String type,
            boolean required,
            Integer maxLength,
            FlowVariableInitialValueDefinition initialValue) {
    }

    public record FlowVariableInitialValueDefinition(String source, String path, JsonNode value) {
    }

    public record FlowTransitionDefinition(
            String transitionId,
            int priority,
            String from,
            String to,
            FlowTriggerDefinition trigger,
            List<FlowAssignmentDefinition> assignments) {
    }

    public record FlowTriggerDefinition(
            String type,
            Long threshold,
            Long delaySeconds,
            String source,
            String path,
            String operator,
            JsonNode value) {
    }

    public record FlowAssignmentDefinition(
            String type,
            String variable,
            String source,
            String path,
            JsonNode value,
            Number increment) {
    }

    public record CallbackDefinition(
            String callbackDefinitionId,
            boolean enabled,
            String triggerState,
            String urlSource,
            String url,
            String requestField,
            String method,
            Map<String, String> headers,
            String payloadTemplate,
            long delayMs,
            int maxRetry,
            List<Long> retryIntervalsMs,
            int totalDeliveryCount,
            List<Long> deliveryOffsetsMs,
            long signaturePolicyVersionId,
            long allowlistPolicyVersionId) {
    }

    public record ScopeDefinition(
            List<String> environments,
            List<String> apps,
            List<String> tenants,
            List<String> testAccounts) {
    }

    public record RuleDefinition(
            String type,
            String operator,
            String key,
            String value,
            boolean caseSensitive) {
    }

    public record ResponseDefinition(
            int httpStatus,
            Map<String, String> headers,
            String bodyTemplate,
            Map<String, String> variableDefaults,
            long delayMs,
            FaultDefinition fault) {
    }

    public record FaultDefinition(String type, long durationMs, String sideEffectPolicy) { }

    public record SignedEnvelope(
            PublishedSnapshot snapshot,
            String checksum,
            String signature,
            String signatureKeyId,
            String signatureAlgorithm) {
    }

    private record Scope(
            List<String> environments,
            List<String> apps,
            List<String> tenants,
            List<String> testAccounts) {
    }
}
