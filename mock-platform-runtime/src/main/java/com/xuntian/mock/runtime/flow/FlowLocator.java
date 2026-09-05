package com.xuntian.mock.runtime.flow;

import java.util.Objects;

public record FlowLocator(
        CompiledFlowDefinition definition,
        CompiledFlowDefinition.Participant participant) {

    public FlowLocator {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(participant, "participant");
    }
}
