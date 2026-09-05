package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.snapshot.FixtureDefinition;

import java.time.Instant;
import java.util.List;

public record PublishedSnapshotDefinition(
        String schemaVersion,
        String releaseId,
        String environment,
        String app,
        Instant createdAt,
        CompiledArtifactManifest compiledArtifacts,
        List<FixtureDefinition.ContractDefinition> compiledContracts,
        List<FixtureDefinition.ScenarioDefinition> compiledScenarios,
        List<FixtureDefinition.FlowDefinition> flowDefinitions) {

    public static final String LEGACY_SCHEMA_VERSION = "1";
    public static final String CURRENT_SCHEMA_VERSION = "2";

    public PublishedSnapshotDefinition(
            String schemaVersion,
            String releaseId,
            String environment,
            String app,
            Instant createdAt,
            CompiledArtifactManifest compiledArtifacts,
            List<FixtureDefinition.ContractDefinition> compiledContracts,
            List<FixtureDefinition.ScenarioDefinition> compiledScenarios) {
        this(schemaVersion, releaseId, environment, app, createdAt, compiledArtifacts,
                compiledContracts, compiledScenarios, null);
    }

    public boolean supportsFlows() {
        return CURRENT_SCHEMA_VERSION.equals(schemaVersion);
    }

    public FixtureDefinition asCompilerInput() {
        return new FixtureDefinition(
                releaseId,
                environment,
                List.of(app),
                createdAt,
                compiledContracts,
                compiledScenarios,
                flowDefinitions == null ? List.of() : flowDefinitions);
    }
}
