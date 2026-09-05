package com.xuntian.mock.control.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.audit.AuditMapper;
import com.xuntian.mock.control.callback.CallbackAdminOperationRecord;
import com.xuntian.mock.control.callback.CallbackTaskPayloadCodec;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.release.ReleaseMapper;
import com.xuntian.mock.control.release.ReleaseEnvelopeVerifier;
import com.xuntian.mock.control.release.ReleaseRecord;
import com.xuntian.mock.control.release.ReleaseSnapshotCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowInstanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private FlowInstanceMapper mapper;
    private ReleaseMapper releases;
    private FlowInstanceService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(FlowInstanceMapper.class);
        releases = mock(ReleaseMapper.class);
        ReleaseEnvelopeVerifier snapshotVerifier = mock(ReleaseEnvelopeVerifier.class);
        AuditService audit = new AuditService(mock(AuditMapper.class), objectMapper);
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        service = new FlowInstanceService(
                mapper, releases, snapshotVerifier, new CallbackTaskPayloadCodec("v1:" + key),
                audit, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
        installRelease(flowDefinition());
        when(mapper.advance(anyLong(), anyLong(), anyInt(), anyString(), anyString(),
                any(), any(), any())).thenReturn(1);
        when(mapper.insertEvent(anyString(), anyLong(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any())).thenReturn(1);
        when(mapper.selectEventId(anyString())).thenReturn(99L);
        when(mapper.completeAdminOperation(anyString(), anyString(), any())).thenReturn(1);
    }

    @Test
    void appliesManualTransitionOnceAndSchedulesNextTimeTransition() throws Exception {
        FlowInstanceRecord flow = flow("PROCESSING", "{\"count\":1}");
        when(mapper.lockByKey("flow-key")).thenReturn(flow);
        AtomicReference<String> checksum = completedOperation("request-1", "FLOW_TRANSITION");

        FlowInstanceService.OperationResult result = service.transition(
                "flow-key", "approve", "request-1",
                new OperatorContext("admin", Set.of("MOCK_ADMIN"), "http-request"));

        assertThat(result.currentState()).isEqualTo("SUCCESS");
        ArgumentCaptor<String> variables = ArgumentCaptor.forClass(String.class);
        verify(mapper).advance(eq(7L), eq(4L), eq(2), eq("SUCCESS"), variables.capture(),
                eq("timeout"), eq(NOW.plusSeconds(30)), eq(NOW));
        assertThat(objectMapper.readTree(variables.getValue()).path("count").decimalValue())
                .isEqualByComparingTo("3");
        verify(mapper).insertEvent(anyString(), eq(7L), eq(2), eq("MANUAL"),
                eq("manual:request-1"), eq("approve"), eq("PROCESSING"), eq("SUCCESS"),
                eq("MANUAL"), eq(5L), eq("admin"), eq(NOW));
        assertThat(checksum.get()).isNotBlank();
    }

    @Test
    void rejectsResetWhenCurrentGenerationHasRunningCallback() {
        FlowInstanceRecord flow = flow("PROCESSING", "{\"count\":1}");
        when(mapper.lockByKey("flow-key")).thenReturn(flow);
        when(mapper.lockPendingCallbacks(7L, 2))
                .thenReturn(List.of(new FlowCallbackState(1, "task-1", "RUNNING")));
        completedOperation("request-2", "FLOW_RESET");

        assertThatThrownBy(() -> service.reset(
                "flow-key", "request-2", true,
                new OperatorContext("admin", Set.of("MOCK_ADMIN"), "http-request")))
                .isInstanceOf(PlatformException.class)
                .extracting(failure -> ((PlatformException) failure).errorCode())
                .isEqualTo(ErrorCode.MOCK_FLOW_OPERATION_BUSY);
        verify(mapper, never()).reset(anyLong(), anyLong(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void timerUsesDeterministicInternalExecutionId() {
        FlowInstanceRecord flow = new FlowInstanceRecord(
                7, "flow-key", "TEST", "app", "provider", "flow", "", "",
                "hmac", "v1", "***", "release-1", 20, "checksum", 2,
                "ACTIVE", "SUCCESS", 5, "{\"count\":3}", 4,
                "timeout", NOW.minusSeconds(1), NOW.plusSeconds(600), NOW.minusSeconds(60), NOW);
        when(mapper.lockNextDue(NOW)).thenReturn(flow);

        assertThat(service.advanceOneDueTimer()).isTrue();
        verify(mapper).insertEvent(anyString(), eq(7L), eq(2), eq("TIMER"),
                eq("timer:7:2:timeout"), eq("timeout"), eq("SUCCESS"), eq("TIMEOUT"),
                eq("TIME"), eq(5L), isNull(), eq(NOW));
    }

    private AtomicReference<String> completedOperation(String requestId, String operation) {
        AtomicReference<String> checksum = new AtomicReference<>();
        when(mapper.insertAdminOperation(eq(requestId), eq(operation), eq("flow-key"), anyString(), eq("admin")))
                .thenAnswer(invocation -> { checksum.set(invocation.getArgument(3)); return 1; });
        when(mapper.lockAdminOperation(requestId)).thenAnswer(invocation -> new CallbackAdminOperationRecord(
                1, requestId, operation, "FLOW_INSTANCE", "flow-key", checksum.get(),
                "IN_TRANSACTION", null, "admin", NOW, null));
        return checksum;
    }

    private void installRelease(ReleaseSnapshotCompiler.FlowDefinition definition) throws Exception {
        ReleaseSnapshotCompiler.PublishedSnapshot snapshot = new ReleaseSnapshotCompiler.PublishedSnapshot(
                "2", "release-1", "TEST", "app", NOW,
                new ReleaseSnapshotCompiler.CompiledArtifactManifest("c", "m", "t", "f"),
                List.of(), List.of(), List.of(definition));
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.set("snapshot", objectMapper.valueToTree(snapshot));
        byte[] bytes = objectMapper.writeValueAsBytes(envelope);
        when(releases.selectRelease("release-1")).thenReturn(new ReleaseRecord(
                "release-1", "r1", "TEST", "app", "PUBLISHED",
                objectMapper.writeValueAsString(envelope), bytes, "snapshot-checksum", "2",
                new byte[0], "key", "SHA256withRSA", null, null, "admin", NOW, "admin", NOW));
    }

    private ReleaseSnapshotCompiler.FlowDefinition flowDefinition() {
        var count = new ReleaseSnapshotCompiler.FlowVariableDefinition(
                "count", "NUMBER", true, null,
                new ReleaseSnapshotCompiler.FlowVariableInitialValueDefinition(
                        "LITERAL", null, JsonNodeFactory.instance.numberNode(1)));
        var approve = new ReleaseSnapshotCompiler.FlowTransitionDefinition(
                "approve", 100, "PROCESSING", "SUCCESS",
                new ReleaseSnapshotCompiler.FlowTriggerDefinition(
                        "MANUAL", null, null, null, null, null, null),
                List.of(new ReleaseSnapshotCompiler.FlowAssignmentDefinition(
                        "INCREMENT_NUMBER", "count", null, null, null, 2)));
        var timeout = new ReleaseSnapshotCompiler.FlowTransitionDefinition(
                "timeout", 50, "SUCCESS", "TIMEOUT",
                new ReleaseSnapshotCompiler.FlowTriggerDefinition(
                        "TIME", null, 30L, null, null, null, null), List.of());
        return new ReleaseSnapshotCompiler.FlowDefinition(
                "10", "20", "provider", "flow", 1, "checksum", "PROCESSING", 3600,
                List.of(), List.of(count), List.of(approve, timeout));
    }

    private FlowInstanceRecord flow(String state, String variables) {
        return new FlowInstanceRecord(
                7, "flow-key", "TEST", "app", "provider", "flow", "", "",
                "hmac", "v1", "***", "release-1", 20, "checksum", 2,
                "ACTIVE", state, 5, variables, 4, null, null,
                NOW.plusSeconds(600), NOW.minusSeconds(60), NOW);
    }
}
