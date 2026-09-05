package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TemplateContext(
        RuntimeRequest request,
        JsonNode body,
        String businessNo,
        Map<String, Object> flowVariables,
        String state,
        Instant now,
        UUID uuid) {

    public TemplateContext {
        flowVariables = flowVariables == null ? Map.of() : Map.copyOf(flowVariables);
    }
}
