package com.xuntian.mock.runtime.snapshot;

import com.xuntian.mock.runtime.engine.ApiKey;
import com.xuntian.mock.runtime.engine.CompiledApi;
import com.xuntian.mock.runtime.flow.CompiledFlowDefinition;
import com.xuntian.mock.runtime.flow.FlowLocator;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeSnapshot(
        String releaseId,
        String environment,
        String app,
        Instant createdAt,
        Map<ApiKey, CompiledApi> apis,
        Map<ApiKey, FlowLocator> flowLocators,
        Map<String, CompiledFlowDefinition> flowDefinitions) {

    public RuntimeSnapshot {
        if (releaseId == null || environment == null || app == null || createdAt == null) {
            throw new IllegalArgumentException("Snapshot identity fields are required");
        }
        if (!safe(releaseId, 64) || !safe(environment, 32) || !safe(app, 128)) {
            throw new IllegalArgumentException("Snapshot identity fields are invalid");
        }
        apis = Map.copyOf(new LinkedHashMap<>(apis));
        if (apis.isEmpty()) {
            throw new IllegalArgumentException("Snapshot must contain compiled APIs");
        }
        flowLocators = Map.copyOf(new LinkedHashMap<>(flowLocators == null ? Map.of() : flowLocators));
        flowDefinitions = Map.copyOf(new LinkedHashMap<>(flowDefinitions == null ? Map.of() : flowDefinitions));
    }

    public RuntimeSnapshot(
            String releaseId,
            String environment,
            String app,
            Instant createdAt,
            Map<ApiKey, CompiledApi> apis) {
        this(releaseId, environment, app, createdAt, apis, Map.of(), Map.of());
    }

    private static boolean safe(String value, int maxLength) {
        return value.length() <= maxLength && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }
}
