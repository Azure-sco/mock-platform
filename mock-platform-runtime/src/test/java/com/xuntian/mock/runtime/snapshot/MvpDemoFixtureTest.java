package com.xuntian.mock.runtime.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.runtime.engine.ApiKey;
import com.xuntian.mock.runtime.engine.RuntimeFault;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MvpDemoFixtureTest {

    @Test
    void compilesNineCoreApisFaultsCallbacksAndTwoCrossApiFlowsWithoutProviderBranches()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FixtureDefinition source;
        try (InputStream input = getClass().getResourceAsStream("/mvp-demo-fixture.json")) {
            source = mapper.readValue(input, FixtureDefinition.class);
        }

        RuntimeSnapshot snapshot = new RuntimeSnapshotCompiler(mapper).compile(source).get(0);
        Map<String, Long> providerApis = snapshot.apis().keySet().stream()
                .collect(Collectors.groupingBy(ApiKey::provider, Collectors.counting()));

        assertThat(snapshot.apis()).hasSize(9);
        assertThat(providerApis).containsExactlyInAnyOrderEntriesOf(Map.of(
                "OA", 3L,
                "CPS_EQB", 3L,
                "SHARED_PLATFORM", 3L));
        assertThat(snapshot.flowDefinitions()).containsOnlyKeys("501", "502");
        assertThat(snapshot.flowDefinitions().get("501").participants()).hasSize(3);
        assertThat(snapshot.flowDefinitions().get("502").participants()).hasSize(1);
        assertThat(snapshot.apis().values())
                .flatExtracting(api -> api.scenarios())
                .anyMatch(scenario -> scenario.fault().type() == RuntimeFault.Type.READ_TIMEOUT)
                .anyMatch(scenario -> scenario.fault().type() == RuntimeFault.Type.CONNECTION_RESET)
                .anyMatch(scenario -> scenario.fault().type() == RuntimeFault.Type.HTTP_ERROR)
                .anyMatch(scenario -> !scenario.callbacks().isEmpty());
    }
}
