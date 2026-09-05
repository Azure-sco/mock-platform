package com.xuntian.mock.runtime.snapshot;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record FixtureDefinition(
        String releaseId,
        String environment,
        List<String> apps,
        Instant createdAt,
        List<ContractDefinition> contracts,
        List<ScenarioDefinition> scenarios,
        List<FlowDefinition> flowDefinitions) {

    public FixtureDefinition(
            String releaseId,
            String environment,
            List<String> apps,
            Instant createdAt,
            List<ContractDefinition> contracts,
            List<ScenarioDefinition> scenarios) {
        this(releaseId, environment, apps, createdAt, contracts, scenarios, List.of());
    }

    public record ContractDefinition(
            Long contractVersionId,
            String provider,
            String api,
            String method,
            String path,
            List<String> contentTypes,
            JsonNode requestSchema,
            JsonNode responseSchema,
            BusinessKeyDefinition businessKeyExtractor) {

        public ContractDefinition(
                String provider,
                String api,
                String method,
                String path,
                List<String> contentTypes,
                JsonNode requestSchema,
                JsonNode responseSchema,
                BusinessKeyDefinition businessKeyExtractor) {
            this(null, provider, api, method, path, contentTypes, requestSchema, responseSchema,
                    businessKeyExtractor);
        }
    }

    public record BusinessKeyDefinition(String source, String path, boolean required, String normalize) { }

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

        public ScenarioDefinition(
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
                ResponseDefinition response) {
            this(scenarioId, scenarioVersionId, scenarioCode, provider, api, priority, effectiveFrom,
                    effectiveTo, scope, matchRules, response, null, null, List.of());
        }

        public ScenarioDefinition(
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
                String flowDefinitionChecksum) {
            this(scenarioId, scenarioVersionId, scenarioCode, provider, api, priority, effectiveFrom,
                    effectiveTo, scope, matchRules, response, flowDefinitionVersionId,
                    flowDefinitionChecksum, List.of());
        }
    }

    public record ScopeDefinition(
            List<String> environments,
            List<String> apps,
            List<String> tenants,
            List<String> testAccounts) { }

    public record RuleDefinition(
            String type,
            String operator,
            String key,
            String value,
            boolean caseSensitive) { }

    public record ResponseDefinition(
            int httpStatus,
            Map<String, String> headers,
            String bodyTemplate,
            Map<String, String> variableDefaults,
            Long delayMs,
            FaultDefinition fault) {

        public ResponseDefinition(
                int httpStatus,
                Map<String, String> headers,
                String bodyTemplate,
                Map<String, String> variableDefaults) {
            this(httpStatus, headers, bodyTemplate, variableDefaults, 0L, null);
        }
    }

    public record FaultDefinition(String type, Long durationMs, String sideEffectPolicy) { }

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
            List<FlowTransitionDefinition> transitions) { }

    public record FlowParticipantDefinition(
            String apiCode,
            long contractVersionId,
            String role,
            boolean createIfAbsent,
            BusinessKeyDefinition businessKeyExtractor) { }

    public record FlowVariableDefinition(
            String name,
            String type,
            boolean required,
            Integer maxLength,
            FlowVariableInitialValueDefinition initialValue) { }

    public record FlowVariableInitialValueDefinition(
            String source,
            String path,
            JsonNode value) { }

    public record FlowTransitionDefinition(
            String transitionId,
            int priority,
            String from,
            String to,
            FlowTriggerDefinition trigger,
            List<FlowAssignmentDefinition> assignments) { }

    public record FlowTriggerDefinition(
            String type,
            Long threshold,
            Long delaySeconds,
            String source,
            String path,
            String operator,
            JsonNode value) { }

    public record FlowAssignmentDefinition(
            String type,
            String variable,
            String source,
            String path,
            JsonNode value,
            Number increment) { }

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
            long allowlistPolicyVersionId) { }
}
