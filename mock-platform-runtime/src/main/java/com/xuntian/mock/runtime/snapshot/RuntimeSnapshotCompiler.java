package com.xuntian.mock.runtime.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.runtime.engine.ApiKey;
import com.xuntian.mock.runtime.engine.CompiledApi;
import com.xuntian.mock.runtime.engine.CompiledContract;
import com.xuntian.mock.runtime.engine.CompiledJsonSchema;
import com.xuntian.mock.runtime.engine.CompiledMatchRule;
import com.xuntian.mock.runtime.engine.CompiledPathTemplate;
import com.xuntian.mock.runtime.engine.CompiledScenario;
import com.xuntian.mock.runtime.engine.CompiledTemplate;
import com.xuntian.mock.runtime.engine.RuntimeFault;
import com.xuntian.mock.runtime.engine.ScenarioScope;
import com.xuntian.mock.runtime.callback.CompiledCallbackDefinition;
import com.xuntian.mock.runtime.flow.CompiledFlowDefinition;
import com.xuntian.mock.runtime.flow.FlowLocator;
import com.xuntian.mock.runtime.release.PublishedSnapshotDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public final class RuntimeSnapshotCompiler {

    private static final int MAX_RULES_PER_SCENARIO = 20;
    private static final int MAX_SCENARIOS_PER_API = 100;
    private final ObjectMapper mapper;

    public RuntimeSnapshotCompiler(ObjectMapper mapper) {
        this.mapper = mapper.copy();
    }

    public List<RuntimeSnapshot> compile(FixtureDefinition source) {
        return compile(source, false);
    }

    private List<RuntimeSnapshot> compile(FixtureDefinition source, boolean requireV2Metadata) {
        requireText(source.releaseId(), "releaseId");
        requireText(source.environment(), "environment");
        if ("PROD".equalsIgnoreCase(source.environment())) {
            throw new IllegalArgumentException("Fixture Snapshot cannot target PROD");
        }
        if (source.createdAt() == null || source.apps() == null || source.apps().isEmpty()) {
            throw new IllegalArgumentException("Snapshot createdAt and apps are required");
        }
        Set<String> snapshotApps = Set.copyOf(source.apps());
        if (snapshotApps.size() != source.apps().size() || snapshotApps.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Snapshot apps must be unique and non-blank");
        }
        Map<ApiKey, CompiledContractEntry> contracts = compileContracts(
                source.contracts(), requireV2Metadata);
        Map<ApiKey, List<CompiledScenario>> scenarios = compileScenarios(
                source.scenarios(), contracts, source.environment(), snapshotApps);
        CompiledFlows flows = compileFlows(source.flowDefinitions(), contracts, scenarios);
        Map<ApiKey, CompiledApi> apis = new LinkedHashMap<>();
        contracts.forEach((key, entry) -> {
            List<CompiledScenario> candidates = scenarios.getOrDefault(key, List.of());
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("Contract has no published Scenario: " + key);
            }
            apis.put(key, new CompiledApi(entry.contractVersionId(), entry.contract(), candidates));
        });
        return source.apps().stream()
                .map(app -> new RuntimeSnapshot(
                        source.releaseId(), source.environment(), app, source.createdAt(), apis,
                        flows.locators(), flows.definitions()))
                .toList();
    }

    public RuntimeSnapshot compile(PublishedSnapshotDefinition source) {
        if (source == null) {
            throw new IllegalArgumentException("Published Snapshot is required");
        }
        boolean v2 = PublishedSnapshotDefinition.CURRENT_SCHEMA_VERSION.equals(source.schemaVersion());
        return compile(source.asCompilerInput(), v2).get(0);
    }

    private Map<ApiKey, CompiledContractEntry> compileContracts(
            List<FixtureDefinition.ContractDefinition> definitions,
            boolean requireVersionId) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("Snapshot contracts are required");
        }
        Map<ApiKey, CompiledContractEntry> result = new LinkedHashMap<>();
        for (FixtureDefinition.ContractDefinition definition : definitions) {
            ApiKey key = new ApiKey(
                    requireText(definition.provider(), "contract.provider"),
                    requireText(definition.api(), "contract.api"));
            if (requireVersionId && (definition.contractVersionId() == null
                    || definition.contractVersionId() <= 0)) {
                throw new IllegalArgumentException("v2 Contract contractVersionId must be positive: " + key);
            }
            CompiledContract.BusinessKeyExtractor extractor = definition.businessKeyExtractor() == null
                    ? null
                    : new CompiledContract.BusinessKeyExtractor(
                            definition.businessKeyExtractor().source(),
                            definition.businessKeyExtractor().path(),
                            definition.businessKeyExtractor().required(),
                            definition.businessKeyExtractor().normalize());
            Set<String> contentTypes = new LinkedHashSet<>(safe(definition.contentTypes()));
            if (definition.requestSchema() != null && !definition.requestSchema().isNull()
                    && !definition.requestSchema().isMissingNode() && contentTypes.stream()
                    .map(value -> value.split(";", 2)[0].trim())
                    .noneMatch("application/json"::equalsIgnoreCase)) {
                throw new IllegalArgumentException("JSON request schema requires application/json: " + key);
            }
            CompiledContract contract = new CompiledContract(
                    requireText(definition.method(), "contract.method"),
                    CompiledPathTemplate.compile(definition.path()),
                    contentTypes,
                    CompiledJsonSchema.compile(definition.requestSchema()),
                    CompiledJsonSchema.compile(definition.responseSchema()),
                    extractor);
            if (result.put(key, new CompiledContractEntry(definition.contractVersionId(), contract)) != null) {
                throw new IllegalArgumentException("Duplicate compiled Contract: " + key);
            }
        }
        return result;
    }

    private Map<ApiKey, List<CompiledScenario>> compileScenarios(
            List<FixtureDefinition.ScenarioDefinition> definitions,
            Map<ApiKey, CompiledContractEntry> contracts,
            String snapshotEnvironment,
            Set<String> snapshotApps) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("Snapshot scenarios are required");
        }
        Map<ApiKey, List<CompiledScenario>> result = new LinkedHashMap<>();
        Set<String> scenarioCodes = new HashSet<>();
        for (FixtureDefinition.ScenarioDefinition definition : definitions) {
            ApiKey key = new ApiKey(
                    requireText(definition.provider(), "scenario.provider"),
                    requireText(definition.api(), "scenario.api"));
            if (!contracts.containsKey(key)) {
                throw new IllegalArgumentException("Scenario references a missing Contract: " + key);
            }
            requireText(definition.scenarioId(), "scenarioId");
            requireText(definition.scenarioVersionId(), "scenarioVersionId");
            String scenarioCode = requireText(definition.scenarioCode(), "scenarioCode");
            if (!scenarioCodes.add(key.provider() + "\u0000" + key.api() + "\u0000" + scenarioCode)) {
                throw new IllegalArgumentException("Duplicate scenarioCode: " + scenarioCode);
            }
            if (definition.effectiveFrom() != null && definition.effectiveTo() != null
                    && !definition.effectiveTo().isAfter(definition.effectiveFrom())) {
                throw new IllegalArgumentException("Scenario effectiveTo must be after effectiveFrom: " + scenarioCode);
            }
            List<FixtureDefinition.RuleDefinition> ruleDefinitions = safe(definition.matchRules());
            if (ruleDefinitions.size() > MAX_RULES_PER_SCENARIO) {
                throw new IllegalArgumentException("Scenario exceeds 20 Match Rules: " + scenarioCode);
            }
            List<CompiledMatchRule> rules = ruleDefinitions.stream()
                    .map(rule -> new CompiledMatchRule(
                            rule.type(), rule.operator(), rule.key(), rule.value(), rule.caseSensitive()))
                    .toList();
            FixtureDefinition.ResponseDefinition response = definition.response();
            if (response == null || response.httpStatus() < 100 || response.httpStatus() > 599) {
                throw new IllegalArgumentException("Scenario response status is invalid: " + scenarioCode);
            }
            Map<String, String> headers = validateHeaders(response.headers());
            FixtureDefinition.ScopeDefinition rawScope = definition.scope();
            if (rawScope == null || safe(rawScope.environments()).isEmpty() || safe(rawScope.apps()).isEmpty()) {
                throw new IllegalArgumentException("Scenario environment and app scope are required: " + scenarioCode);
            }
            Set<String> environments = Set.copyOf(rawScope.environments());
            Set<String> apps = Set.copyOf(rawScope.apps());
            if (!environments.equals(Set.of(snapshotEnvironment)) || !snapshotApps.containsAll(apps)
                    || environments.stream().anyMatch(value -> "PROD".equalsIgnoreCase(value))) {
                throw new IllegalArgumentException("Scenario scope exceeds Snapshot scope: " + scenarioCode);
            }
            ScenarioScope scope = new ScenarioScope(
                    environments,
                    apps,
                    Set.copyOf(safe(rawScope.tenants())),
                    Set.copyOf(safe(rawScope.testAccounts())));
            CompiledScenario scenario = new CompiledScenario(
                    definition.scenarioId(),
                    definition.scenarioVersionId(),
                    scenarioCode,
                    definition.priority(),
                    definition.effectiveFrom(),
                    definition.effectiveTo(),
                    scope,
                    rules,
                    response.httpStatus(),
                    headers,
                    CompiledTemplate.compile(response.bodyTemplate(), response.variableDefaults(), mapper),
                    response.delayMs() == null ? 0 : response.delayMs(),
                    compileFault(response.fault(), response.httpStatus()),
                    definition.flowDefinitionVersionId(),
                    definition.flowDefinitionChecksum(),
                    safe(definition.callbacks()).stream()
                            .map(callback -> CompiledCallbackDefinition.compile(
                                    callback, snapshotEnvironment, mapper))
                            .toList());
            List<CompiledScenario> candidates = result.computeIfAbsent(key, ignored -> new ArrayList<>());
            candidates.add(scenario);
            if (candidates.size() > MAX_SCENARIOS_PER_API) {
                throw new IllegalArgumentException("API exceeds 100 Scenarios: " + key);
            }
        }
        return result;
    }

    private RuntimeFault compileFault(FixtureDefinition.FaultDefinition source, int httpStatus) {
        if (source == null) return RuntimeFault.none();
        try {
            RuntimeFault.Type type = RuntimeFault.Type.valueOf(
                    requireText(source.type(), "fault.type").toUpperCase(Locale.ROOT));
            RuntimeFault.SideEffectPolicy policy = RuntimeFault.SideEffectPolicy.valueOf(
                    requireText(source.sideEffectPolicy(), "fault.sideEffectPolicy")
                            .toUpperCase(Locale.ROOT));
            if (type == RuntimeFault.Type.HTTP_ERROR && httpStatus < 400) {
                throw new IllegalArgumentException("HTTP_ERROR requires a 4xx or 5xx status");
            }
            return new RuntimeFault(type, source.durationMs() == null ? 0 : source.durationMs(), policy);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Scenario fault configuration is invalid", failure);
        }
    }

    private CompiledFlows compileFlows(
            List<FixtureDefinition.FlowDefinition> definitions,
            Map<ApiKey, CompiledContractEntry> contracts,
            Map<ApiKey, List<CompiledScenario>> scenarios) {
        List<FixtureDefinition.FlowDefinition> safeDefinitions = safe(definitions);
        Map<String, CompiledFlowDefinition> byVersion = new LinkedHashMap<>();
        Map<ApiKey, FlowLocator> locators = new LinkedHashMap<>();
        for (FixtureDefinition.FlowDefinition source : safeDefinitions) {
            CompiledFlowDefinition definition = CompiledFlowDefinition.compile(source, mapper);
            if (byVersion.put(definition.flowDefinitionVersionId(), definition) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Flow Definition Version: " + definition.flowDefinitionVersionId());
            }
            for (CompiledFlowDefinition.Participant participant : definition.participants().values()) {
                ApiKey key = new ApiKey(definition.provider(), participant.apiCode());
                CompiledContractEntry contract = contracts.get(key);
                if (contract == null || contract.contractVersionId() == null
                        || contract.contractVersionId() != participant.contractVersionId()) {
                    throw new IllegalArgumentException(
                            "Flow Participant Contract Version does not match compiled Contract: " + key);
                }
                FlowLocator previous = locators.put(key, new FlowLocator(definition, participant));
                if (previous != null) {
                    throw new IllegalArgumentException("API can participate in only one Flow per Snapshot: " + key);
                }
            }
        }
        for (Map.Entry<ApiKey, List<CompiledScenario>> entry : scenarios.entrySet()) {
            for (CompiledScenario scenario : entry.getValue()) {
                if (scenario.flowDefinitionVersionId() == null) continue;
                CompiledFlowDefinition definition = byVersion.get(scenario.flowDefinitionVersionId());
                FlowLocator locator = locators.get(entry.getKey());
                if (definition == null || locator == null || locator.definition() != definition
                        || !definition.checksum().equals(scenario.flowDefinitionChecksum())) {
                    throw new IllegalArgumentException(
                            "Scenario Flow binding does not match compiled Flow Definition: "
                                    + scenario.scenarioVersionId());
                }
            }
        }
        for (Map.Entry<ApiKey, FlowLocator> entry : locators.entrySet()) {
            boolean bound = scenarios.getOrDefault(entry.getKey(), List.of()).stream()
                    .anyMatch(scenario -> entry.getValue().definition().flowDefinitionVersionId()
                            .equals(scenario.flowDefinitionVersionId()));
            if (!bound) {
                throw new IllegalArgumentException(
                        "Flow Participant has no Scenario bound to its Flow version: " + entry.getKey());
            }
        }
        return new CompiledFlows(locators, byVersion);
    }

    private Map<String, String> validateHeaders(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (name.isBlank() || value == null || name.contains("\r") || name.contains("\n")
                    || value.contains("\r") || value.contains("\n")
                    || lower.equals("content-length") || lower.startsWith("x-mock-")) {
                throw new IllegalArgumentException("Unsafe response header: " + name);
            }
            result.put(name, value);
        });
        return result;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record CompiledContractEntry(Long contractVersionId, CompiledContract contract) { }

    private record CompiledFlows(
            Map<ApiKey, FlowLocator> locators,
            Map<String, CompiledFlowDefinition> definitions) { }
}
