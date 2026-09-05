package com.xuntian.mock.runtime.snapshot;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.engine.ApiKey;
import com.xuntian.mock.runtime.engine.CompiledApi;
import com.xuntian.mock.runtime.engine.CompiledContract;
import com.xuntian.mock.runtime.engine.CompiledJsonSchema;
import com.xuntian.mock.runtime.engine.CompiledPathTemplate;
import com.xuntian.mock.runtime.engine.CompiledScenario;
import com.xuntian.mock.runtime.engine.CompiledTemplate;
import com.xuntian.mock.runtime.engine.ScenarioScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeSnapshotTest {

    @Test
    void defensivelyCopiesNestedCollections() {
        List<CompiledScenario> scenarios = new java.util.ArrayList<>();
        scenarios.add(new CompiledScenario(
                "sc-1", "scv-1", "scenario-1", 1, null, null,
                new ScenarioScope(Set.of(), Set.of(), Set.of(), Set.of()), List.of(), 200, Map.of(),
                CompiledTemplate.compile("{\"ok\":true}", Map.of(), new ObjectMapper())));
        CompiledContract contract = new CompiledContract(
                "GET", CompiledPathTemplate.compile("/path"), Set.of(),
                CompiledJsonSchema.compile(null), CompiledJsonSchema.compile(null), null);
        CompiledApi api = new CompiledApi(contract, scenarios);
        Map<ApiKey, CompiledApi> apis = new LinkedHashMap<>();
        apis.put(new ApiKey("P", "A"), api);

        RuntimeSnapshot snapshot = new RuntimeSnapshot("rel", "TEST", "app", Instant.EPOCH, apis);
        apis.clear();
        scenarios.clear();

        assertThat(snapshot.apis()).hasSize(1);
        assertThat(snapshot.apis().values().iterator().next().scenarios()).hasSize(1);
        assertThatThrownBy(() -> snapshot.apis().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void repositoryFailsClosedWhenNoSnapshotExists() {
        RuntimeSnapshotRepository repository = new FailClosedRuntimeSnapshotRepository();

        assertThatThrownBy(() -> repository.require("TEST", "app"))
                .isInstanceOfSatisfying(PlatformException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.MOCK_RELEASE_UNAVAILABLE));
    }
}
