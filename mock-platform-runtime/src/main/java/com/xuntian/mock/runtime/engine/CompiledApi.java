package com.xuntian.mock.runtime.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record CompiledApi(Long contractVersionId, CompiledContract contract, List<CompiledScenario> scenarios) {

    private static final Comparator<CompiledScenario> ORDER = Comparator
            .comparingInt(CompiledScenario::priority).reversed()
            .thenComparing(CompiledScenario::scenarioCode);

    public CompiledApi {
        Objects.requireNonNull(contract, "contract");
        if (scenarios == null || scenarios.isEmpty() || scenarios.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Compiled API must contain published Scenarios");
        }
        scenarios = scenarios.stream().sorted(ORDER).toList();
    }

    public CompiledApi(CompiledContract contract, List<CompiledScenario> scenarios) {
        this(null, contract, scenarios);
    }
}
