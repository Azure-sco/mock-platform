package com.xuntian.mock.runtime.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.runtime.engine.RuntimeRequest;
import com.xuntian.mock.runtime.snapshot.FixtureDefinition;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshot;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshotCompiler;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MvpCrossApiFlowTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final FlowTransitionService transitions = new FlowTransitionService(mapper);

    @Test
    void oaCreateQueryCommandAdvancesOneFixedFlowToApproved() throws Exception {
        CompiledFlowDefinition definition = snapshot().flowDefinitions().get("501");
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        RuntimeRequest create = request(
                "OA", "OA_SETTLE_CREATE", "POST",
                "/api/km-review/kmReviewRestService/addReviewNew", "mr-oa-create",
                Map.of("X-Mock-Business-No", List.of("SETTLE-1001")), Map.of(), new byte[0]);
        FlowInstance flow = transitions.create(
                1, new FlowTransitionService.FlowKey("fk", "bh", "v1", "S***1"),
                "rel-mvp-demo-v1", definition, create, mapper.nullNode(), now);

        flow = transitions.applySdk(
                flow, definition, definition.requireParticipant("OA_SETTLE_CREATE"),
                create, mapper.nullNode(), 1, now).instance();
        assertThat(flow.currentState()).isEqualTo("CREATED");

        RuntimeRequest query = request(
                "OA", "OA_NUMBER_QUERY", "GET",
                "/api/tcl-cpms/cpmsAuditRestService/getAuditInfosNew", "mr-oa-query",
                Map.of(), Map.of("fdIdList", List.of("SETTLE-1001")), new byte[0]);
        FlowTransitionService.Result queried = transitions.applySdk(
                flow, definition, definition.requireParticipant("OA_NUMBER_QUERY"),
                query, mapper.nullNode(), 1, now.plusSeconds(1));
        flow = queried.instance();
        assertThat(flow.currentState()).isEqualTo("PROCESSING");
        assertThat(flow.queryCount()).isEqualTo(1);
        assertThat(queried.events()).extracting(FlowEvent::transitionId)
                .containsExactly("oa-query-processing");

        RuntimeRequest command = request(
                "OA", "OA_PROCESS_COMMAND", "POST",
                "/api/km-review/kmReviewRestService/approveProcessNew", "mr-oa-command",
                Map.of(
                        "X-Mock-Business-No", List.of("SETTLE-1001"),
                        "X-Business-Action", List.of("approve")),
                Map.of(), new byte[0]);
        FlowTransitionService.Result approved = transitions.applySdk(
                flow, definition, definition.requireParticipant("OA_PROCESS_COMMAND"),
                command, mapper.nullNode(), 1, now.plusSeconds(2));

        assertThat(approved.instance().currentState()).isEqualTo("APPROVED");
        assertThat(approved.events()).extracting(FlowEvent::transitionId).containsExactly("oa-approve");
    }

    @Test
    void sharedSettlementMovesFromProcessingToSuccessOnItsDueTimer() throws Exception {
        CompiledFlowDefinition definition = snapshot().flowDefinitions().get("502");
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        byte[] body = "{\"uniqueNo\":\"SETTLE-7\",\"businessType\":\"YWF\"}".getBytes();
        RuntimeRequest create = request(
                "SHARED_PLATFORM", "SHARED_SETTLEMENT_PUSH", "POST",
                "/api/web/settlement/open/xts/pushBillFeesToFssc",
                "mr-shared-create", Map.of("Content-Type", List.of("application/json")),
                Map.of(), body);
        FlowInstance flow = transitions.create(
                2, new FlowTransitionService.FlowKey("fk2", "bh2", "v1", "O***7"),
                "rel-mvp-demo-v1", definition, create, mapper.readTree(body), now);

        assertThat(flow.currentState()).isEqualTo("PROCESSING");
        assertThat(flow.pendingTransitionId()).isEqualTo("shared-time-success");
        FlowTransitionService.Result completed = transitions.applyTimer(
                flow, definition, now.plusSeconds(1));

        assertThat(completed.instance().currentState()).isEqualTo("SUCCESS");
        assertThat(completed.events()).extracting(FlowEvent::internalExecutionId)
                .containsExactly("timer:2:1:shared-time-success");
    }

    @Test
    void onlyCreateParticipantWithCreateIfAbsentMayCreateOrReactivateFlow() {
        var extractor = (CompiledFlowDefinition.BusinessKeyExtractor) null;

        assertThat(RuntimeFlowExecutionService.mayCreate(new CompiledFlowDefinition.Participant(
                "create", 1, CompiledFlowDefinition.Role.CREATE, true, extractor))).isTrue();
        assertThat(RuntimeFlowExecutionService.mayCreate(new CompiledFlowDefinition.Participant(
                "create-disabled", 1, CompiledFlowDefinition.Role.CREATE, false, extractor))).isFalse();
        assertThat(RuntimeFlowExecutionService.mayCreate(new CompiledFlowDefinition.Participant(
                "query", 1, CompiledFlowDefinition.Role.QUERY, true, extractor))).isFalse();
    }

    private RuntimeSnapshot snapshot() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/mvp-demo-fixture.json")) {
            FixtureDefinition source = mapper.readValue(input, FixtureDefinition.class);
            return new RuntimeSnapshotCompiler(mapper).compile(source).get(0);
        }
    }

    private RuntimeRequest request(
            String provider,
            String api,
            String method,
            String path,
            String requestId,
            Map<String, List<String>> headers,
            Map<String, List<String>> query,
            byte[] body) {
        return new RuntimeRequest(
                "TEST", "sample-jdk17", null, null, provider, api, method, path,
                headers.getOrDefault("Content-Type", List.of()).stream().findFirst().orElse(null),
                headers, query, body, requestId, "trace-" + requestId);
    }
}
