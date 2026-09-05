package com.xuntian.mock.runtime.engine;

import com.xuntian.mock.runtime.callback.CompiledCallbackDefinition;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CompiledScenario {

    private final String scenarioId;
    private final String scenarioVersionId;
    private final String scenarioCode;
    private final int priority;
    private final Instant effectiveFrom;
    private final Instant effectiveTo;
    private final ScenarioScope scope;
    private final List<CompiledMatchRule> rules;
    private final int httpStatus;
    private final Map<String, String> responseHeaders;
    private final CompiledTemplate template;
    private final long responseDelayMs;
    private final RuntimeFault fault;
    private final String flowDefinitionVersionId;
    private final String flowDefinitionChecksum;
    private final List<CompiledCallbackDefinition> callbacks;

    public CompiledScenario(
            String scenarioId,
            String scenarioVersionId,
            String scenarioCode,
            int priority,
            Instant effectiveFrom,
            Instant effectiveTo,
            ScenarioScope scope,
            List<CompiledMatchRule> rules,
            int httpStatus,
            Map<String, String> responseHeaders,
            CompiledTemplate template,
            long responseDelayMs,
            RuntimeFault fault,
            String flowDefinitionVersionId,
            String flowDefinitionChecksum,
            List<CompiledCallbackDefinition> callbacks) {
        if (!safeId(scenarioId, 64) || !safeId(scenarioVersionId, 64) || !safeId(scenarioCode, 128)) {
            throw new IllegalArgumentException("Scenario identifiers are invalid");
        }
        this.scenarioId = scenarioId;
        this.scenarioVersionId = scenarioVersionId;
        this.scenarioCode = scenarioCode;
        this.priority = priority;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.scope = scope;
        this.rules = List.copyOf(rules);
        this.httpStatus = httpStatus;
        this.responseHeaders = Map.copyOf(new LinkedHashMap<>(responseHeaders));
        this.template = template;
        if (responseDelayMs < 0 || responseDelayMs > RuntimeFault.MAX_DURATION_MS) {
            throw new IllegalArgumentException("Scenario delayMs must be from 0 to 60000");
        }
        this.responseDelayMs = responseDelayMs;
        this.fault = fault == null ? RuntimeFault.none() : fault;
        if ((flowDefinitionVersionId == null) != (flowDefinitionChecksum == null)) {
            throw new IllegalArgumentException("Scenario Flow binding fields must be set together");
        }
        if (flowDefinitionChecksum != null && !flowDefinitionChecksum.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Scenario Flow checksum is invalid");
        }
        this.flowDefinitionVersionId = flowDefinitionVersionId;
        this.flowDefinitionChecksum = flowDefinitionChecksum;
        this.callbacks = List.copyOf(callbacks == null ? List.of() : callbacks);
        if (!this.callbacks.isEmpty() && flowDefinitionVersionId == null) {
            throw new IllegalArgumentException("Callback requires a Scenario Flow binding");
        }
    }

    public CompiledScenario(
            String scenarioId,
            String scenarioVersionId,
            String scenarioCode,
            int priority,
            Instant effectiveFrom,
            Instant effectiveTo,
            ScenarioScope scope,
            List<CompiledMatchRule> rules,
            int httpStatus,
            Map<String, String> responseHeaders,
            CompiledTemplate template,
            String flowDefinitionVersionId,
            String flowDefinitionChecksum) {
        this(scenarioId, scenarioVersionId, scenarioCode, priority, effectiveFrom, effectiveTo,
                scope, rules, httpStatus, responseHeaders, template, 0, RuntimeFault.none(),
                flowDefinitionVersionId, flowDefinitionChecksum, List.of());
    }

    public CompiledScenario(
            String scenarioId,
            String scenarioVersionId,
            String scenarioCode,
            int priority,
            Instant effectiveFrom,
            Instant effectiveTo,
            ScenarioScope scope,
            List<CompiledMatchRule> rules,
            int httpStatus,
            Map<String, String> responseHeaders,
            CompiledTemplate template) {
        this(scenarioId, scenarioVersionId, scenarioCode, priority, effectiveFrom, effectiveTo,
                scope, rules, httpStatus, responseHeaders, template, 0, RuntimeFault.none(),
                null, null, List.of());
    }

    public boolean matches(RuntimeRequest request, CompiledContract.ContractMatch contract, Instant now) {
        return matches(request, contract, now, false);
    }

    public boolean matches(
            RuntimeRequest request,
            CompiledContract.ContractMatch contract,
            Instant now,
            boolean ignoreEffectiveWindow) {
        return scope.matches(request)
                && (ignoreEffectiveWindow || effectiveFrom == null || !now.isBefore(effectiveFrom))
                && (ignoreEffectiveWindow || effectiveTo == null || now.isBefore(effectiveTo))
                && rules.stream().allMatch(rule -> rule.matches(request, contract.body(), contract.businessNo()));
    }

    public String scenarioId() { return scenarioId; }
    public String scenarioVersionId() { return scenarioVersionId; }
    public String scenarioCode() { return scenarioCode; }
    public int priority() { return priority; }
    public int httpStatus() { return httpStatus; }
    public Map<String, String> responseHeaders() { return responseHeaders; }
    public CompiledTemplate template() { return template; }
    public long responseDelayMs() { return responseDelayMs; }
    public RuntimeFault fault() { return fault; }
    public String flowDefinitionVersionId() { return flowDefinitionVersionId; }
    public String flowDefinitionChecksum() { return flowDefinitionChecksum; }
    public List<CompiledCallbackDefinition> callbacks() { return callbacks; }

    private static boolean safeId(String value, int maxLength) {
        return value != null && value.length() <= maxLength
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }
}
