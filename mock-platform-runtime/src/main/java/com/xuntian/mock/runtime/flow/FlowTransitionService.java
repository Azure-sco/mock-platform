package com.xuntian.mock.runtime.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.engine.RuntimeRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FlowTransitionService {

    private final ObjectMapper mapper;

    public FlowTransitionService(ObjectMapper mapper) {
        this.mapper = mapper.copy();
    }

    public FlowInstance create(
            long id,
            FlowKey key,
            String releaseId,
            CompiledFlowDefinition definition,
            RuntimeRequest request,
            JsonNode body,
            Instant now) {
        Map<String, Object> variables = definition.initializeVariables(request, body, mapper);
        CompiledFlowDefinition.PendingTransition pending = definition.pendingTransition(
                definition.initialState(), now);
        return new FlowInstance(
                id,
                key.flowKey(),
                request.environment(),
                request.app(),
                request.provider(),
                definition.flowCode(),
                blank(request.tenant()),
                blank(request.testAccount()),
                key.businessNoHmac(),
                key.hmacKeyVersion(),
                key.businessNoMasked(),
                releaseId,
                definition.flowDefinitionVersionId(),
                definition.checksum(),
                1,
                FlowInstance.Status.ACTIVE,
                definition.initialState(),
                0,
                variables,
                0,
                pending.transitionId(),
                pending.executeAt(),
                now.plus(definition.ttl()),
                now,
                now);
    }

    public Result applySdk(
            FlowInstance source,
            CompiledFlowDefinition definition,
            CompiledFlowDefinition.Participant participant,
            RuntimeRequest request,
            JsonNode body,
            int executionGeneration,
            Instant now) {
        requireActive(source, now);
        FlowInstance current = source;
        List<FlowEvent> events = new ArrayList<>();

        var due = definition.dueTimeTransition(
                current.currentState(), current.pendingTransitionId(), current.nextTransitionAt(), now);
        if (due.isPresent()) {
            CompiledFlowDefinition.Transition transition = due.get();
            String internalId = "timer:" + current.id() + ':' + current.generation() + ':'
                    + transition.transitionId();
            TransitionApplied applied = apply(current, definition, transition, request, body, now);
            current = applied.instance();
            events.add(event(
                    current, FlowEvent.SourceType.TIMER, null, null, internalId, null,
                    transition, applied.fromState(), "TIME", null, now));
        }

        long queryCount = current.queryCount();
        if (participant.role() == CompiledFlowDefinition.Role.QUERY) queryCount++;
        String before = current.currentState();
        var selected = definition.selectRequestTransition(
                before, participant.role(), queryCount, request, body);
        if (selected.isPresent()) {
            CompiledFlowDefinition.Transition transition = selected.get();
            TransitionApplied applied = apply(
                    current.progressed(
                            current.currentState(), queryCount, current.variables(),
                            current.pendingTransitionId(), current.nextTransitionAt(), now),
                    definition, transition, request, body, now);
            current = applied.instance();
            events.add(event(
                    current, FlowEvent.SourceType.SDK, request.mockRequestId(), executionGeneration,
                    null, request.api(), transition, applied.fromState(), participant.role().name(),
                    transition.trigger().type().name(), now));
        } else {
            current = current.progressed(
                    current.currentState(), queryCount, current.variables(),
                    current.pendingTransitionId(), current.nextTransitionAt(), now);
            events.add(event(
                    current, FlowEvent.SourceType.SDK, request.mockRequestId(), executionGeneration,
                    null, request.api(), null, before, participant.role().name(), null, now));
        }
        return new Result(current, events);
    }

    public Result applyManual(
            FlowInstance source,
            CompiledFlowDefinition definition,
            String transitionId,
            String requestId,
            String operator,
            Instant now) {
        requireActive(source, now);
        CompiledFlowDefinition.Transition transition = definition
                .manualTransition(source.currentState(), transitionId)
                .orElseThrow(() -> new PlatformException(
                        ErrorCode.INVALID_STATE,
                        "No MANUAL transition is available from the current Flow state"));
        TransitionApplied applied = apply(source, definition, transition, null, null, now);
        FlowEvent event = event(
                applied.instance(), FlowEvent.SourceType.MANUAL, null, null,
                "manual:" + requestId, null, transition, applied.fromState(), "MANUAL", operator, now);
        return new Result(applied.instance(), List.of(event));
    }

    public Result applyTimer(
            FlowInstance source,
            CompiledFlowDefinition definition,
            Instant now) {
        requireActive(source, now);
        CompiledFlowDefinition.Transition transition = definition
                .dueTimeTransition(
                        source.currentState(), source.pendingTransitionId(), source.nextTransitionAt(), now)
                .orElseThrow(() -> new PlatformException(
                        ErrorCode.INVALID_STATE,
                        "Flow has no due TIME transition"));
        TransitionApplied applied = apply(source, definition, transition, null, null, now);
        String internalId = "timer:" + source.id() + ':' + source.generation() + ':'
                + transition.transitionId();
        FlowEvent event = event(
                applied.instance(), FlowEvent.SourceType.TIMER, null, null, internalId, null,
                transition, applied.fromState(), "TIME", null, now);
        return new Result(applied.instance(), List.of(event));
    }

    public FlowEvent reactivateEvent(
            FlowInstance before,
            FlowInstance after,
            RuntimeRequest request,
            int executionGeneration,
            Instant now) {
        return event(
                after, FlowEvent.SourceType.SDK, request.mockRequestId(), executionGeneration,
                null, request.api(), null, before.currentState(), "REACTIVATE", null, now);
    }

    private TransitionApplied apply(
            FlowInstance source,
            CompiledFlowDefinition definition,
            CompiledFlowDefinition.Transition transition,
            RuntimeRequest request,
            JsonNode body,
            Instant now) {
        if (!transition.from().equals(source.currentState())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Flow transition source state changed");
        }
        Map<String, Object> variables = definition.applyAssignments(
                source.variables(), transition, request, body, mapper);
        CompiledFlowDefinition.PendingTransition pending = definition.pendingTransition(transition.to(), now);
        FlowInstance result = source.progressed(
                transition.to(), source.queryCount(), variables,
                pending.transitionId(), pending.executeAt(), now);
        return new TransitionApplied(result, transition.from());
    }

    private static FlowEvent event(
            FlowInstance instance,
            FlowEvent.SourceType sourceType,
            String mockRequestId,
            Integer executionGeneration,
            String internalExecutionId,
            String sourceApi,
            CompiledFlowDefinition.Transition transition,
            String fromState,
            String eventType,
            String operator,
            Instant now) {
        String stable = sourceType + "|" + instance.id() + '|' + instance.generation() + '|'
                + (mockRequestId == null ? internalExecutionId : mockRequestId) + '|' + eventType;
        String eventId = UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)).toString();
        return new FlowEvent(
                0, eventId, instance.id(), instance.generation(), sourceType, mockRequestId,
                executionGeneration, internalExecutionId, sourceApi,
                transition == null ? null : transition.transitionId(), fromState,
                instance.currentState(), eventType, instance.queryCount(), operator, now);
    }

    private static void requireActive(FlowInstance instance, Instant now) {
        if (instance.status() != FlowInstance.Status.ACTIVE || !instance.expireAt().isAfter(now)) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Flow is not active");
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    public record FlowKey(
            String flowKey,
            String businessNoHmac,
            String hmacKeyVersion,
            String businessNoMasked) { }

    public record Result(FlowInstance instance, List<FlowEvent> events) {
        public Result {
            events = List.copyOf(events);
        }
    }

    private record TransitionApplied(FlowInstance instance, String fromState) { }
}
