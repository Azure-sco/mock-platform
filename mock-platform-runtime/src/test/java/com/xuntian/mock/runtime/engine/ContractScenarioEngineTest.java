package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.snapshot.FixtureDefinition;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshot;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshotCompiler;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractScenarioEngineTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ContractScenarioEngine engine = new ContractScenarioEngine(mapper);

    @Test
    void executesOnlyCompiledFixtureSnapshotAndReturnsDeterministicScenario() throws Exception {
        RuntimeSnapshot snapshot = fixture();
        RuntimeRequest request = request("EQB");

        RuntimeExecution result = engine.execute(
                snapshot,
                request,
                Instant.parse("2026-08-31T01:00:00Z"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"));

        assertThat(result.releaseId()).isEqualTo("rel-local-fixture-v1");
        assertThat(result.scenarioId()).isEqualTo("sc-cps-sign-success");
        assertThat(mapper.readTree(result.body()).path("data").path("settleId").asInt()).isEqualTo(42);
    }

    @Test
    void noMatchNeverFallsBackToFormerHardcodedDispatcher() throws Exception {
        RuntimeSnapshot snapshot = fixture();

        assertThatThrownBy(() -> engine.execute(
                snapshot, request("OTHER"), Instant.now(), UUID.randomUUID()))
                .isInstanceOfSatisfying(PlatformException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.MOCK_NO_MATCH));
    }

    @Test
    void scenarioRulesAreAndedAndScopeAndEffectiveWindowAreEnforced() throws Exception {
        RuntimeRequest request = request("EQB");
        CompiledScenario scenario = scenario(
                "eligible", 10,
                new ScenarioScope(Set.of("TEST"), Set.of("sample-jdk17"), Set.of(), Set.of()),
                List.of(
                        new CompiledMatchRule("QUERY", "EQ", "channel", "EQB", true),
                        new CompiledMatchRule("HEADER", "EQ", "domain", "cps-test", true)),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(scenario.matches(
                request,
                new CompiledContract.ContractMatch(mapper.readTree(request.body()), null),
                Instant.parse("2026-08-31T00:00:00Z"))).isTrue();
        assertThat(scenario.matches(
                request("OTHER"),
                new CompiledContract.ContractMatch(mapper.readTree(request.body()), null),
                Instant.parse("2026-08-31T00:00:00Z"))).isFalse();
        assertThat(scenario.matches(
                request,
                new CompiledContract.ContractMatch(mapper.readTree(request.body()), null),
                Instant.parse("2028-01-01T00:00:00Z"))).isFalse();
    }

    @Test
    void tenantAndTestAccountScopeCannotLeakAcrossLocalIdentities() {
        ScenarioScope scope = new ScenarioScope(
                Set.of("TEST"), Set.of("sample-jdk17"), Set.of("tenant-a"), Set.of("tester-01"));

        assertThat(scope.matches(request("EQB", "tenant-a", "tester-01"))).isTrue();
        assertThat(scope.matches(request("EQB", "tenant-b", "tester-01"))).isFalse();
        assertThat(scope.matches(request("EQB", "tenant-a", "tester-02"))).isFalse();
        assertThat(scope.matches(request("EQB", null, null))).isFalse();
    }

    @Test
    void ordersByDescendingPriorityThenAscendingScenarioCode() {
        CompiledScenario low = scenario("z-low", 1, wildcardScope(), List.of(), null, null);
        CompiledScenario beta = scenario("b-beta", 10, wildcardScope(), List.of(), null, null);
        CompiledScenario alpha = scenario("a-alpha", 10, wildcardScope(), List.of(), null, null);

        CompiledApi api = new CompiledApi(contract(), List.of(low, beta, alpha));

        assertThat(api.scenarios()).extracting(CompiledScenario::scenarioCode)
                .containsExactly("a-alpha", "b-beta", "z-low");
    }

    private RuntimeSnapshot fixture() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("runtime-fixture.json")) {
            FixtureDefinition definition = mapper.readValue(input, FixtureDefinition.class);
            return new RuntimeSnapshotCompiler(mapper).compile(definition).stream()
                    .filter(snapshot -> snapshot.app().equals("sample-jdk17"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private RuntimeRequest request(String channel) {
        return request(channel, null, null);
    }

    private RuntimeRequest request(String channel, String tenant, String testAccount) {
        return new RuntimeRequest(
                "TEST", "sample-jdk17", tenant, testAccount, "CPS_EQB", "CPS_SIGN_CREATE_START",
                "POST", "/sign/create-and-start", "application/json; charset=UTF-8",
                Map.of("domain", List.of("cps-test")),
                Map.of("channel", List.of(channel)),
                "{\"settleId\":42}".getBytes(), "mr-unit-1", "trace-unit-1");
    }

    private CompiledScenario scenario(
            String code,
            int priority,
            ScenarioScope scope,
            List<CompiledMatchRule> rules,
            Instant from,
            Instant to) {
        return new CompiledScenario(
                "sc-" + code, "scv-" + code, code, priority, from, to, scope, rules,
                200, Map.of("Content-Type", "application/json"),
                CompiledTemplate.compile("{\"ok\":true}", Map.of(), mapper));
    }

    private ScenarioScope wildcardScope() {
        return new ScenarioScope(Set.of(), Set.of(), Set.of(), Set.of());
    }

    private CompiledContract contract() {
        return new CompiledContract(
                "POST",
                CompiledPathTemplate.compile("/path"),
                Set.of("application/json"),
                CompiledJsonSchema.compile(null),
                CompiledJsonSchema.compile(null),
                null);
    }
}
