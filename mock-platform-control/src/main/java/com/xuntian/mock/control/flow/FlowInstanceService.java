package com.xuntian.mock.control.flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PageResult;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.callback.CallbackAdminOperationRecord;
import com.xuntian.mock.control.callback.CallbackTaskPayloadCodec;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.release.ActiveReleaseRecord;
import com.xuntian.mock.control.release.ReleaseMapper;
import com.xuntian.mock.control.release.ReleaseEnvelopeVerifier;
import com.xuntian.mock.control.release.ReleaseRecord;
import com.xuntian.mock.control.release.ReleaseSnapshotCompiler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FlowInstanceService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("ACTIVE", "EXPIRED", "DELETED");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    private final FlowInstanceMapper mapper;
    private final ReleaseMapper releases;
    private final ReleaseEnvelopeVerifier snapshotVerifier;
    private final CallbackTaskPayloadCodec callbackCodec;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FlowInstanceService(
            FlowInstanceMapper mapper,
            ReleaseMapper releases,
            ReleaseEnvelopeVerifier snapshotVerifier,
            CallbackTaskPayloadCodec callbackCodec,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mapper = mapper;
        this.releases = releases;
        this.snapshotVerifier = snapshotVerifier;
        this.callbackCodec = callbackCodec;
        this.audit = audit;
        this.objectMapper = objectMapper.copy();
        this.clock = clock;
    }

    public PageResult<FlowInstanceView> find(FlowInstanceFilter filter, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw invalid("Invalid page or size");
        if (filter.status() != null && !STATUSES.contains(filter.status())) throw invalid("Flow status is invalid");
        long total = mapper.count(filter);
        List<FlowInstanceView> records = mapper.selectPage(filter, size, (long) page * size)
                .stream().map(this::view).toList();
        return new PageResult<>(records, total, page, size);
    }

    public FlowInstanceView detail(String rawFlowKey) {
        FlowInstanceRecord value = require(rawFlowKey);
        return view(value);
    }

    public List<FlowEventRecord> events(String rawFlowKey) {
        return mapper.selectEvents(require(rawFlowKey).id());
    }

    @Transactional
    public OperationResult transition(
            String rawFlowKey,
            String rawTransitionId,
            String rawRequestId,
            OperatorContext operator) {
        String flowKey = required(rawFlowKey, "flowKey", 128);
        String transitionId = required(rawTransitionId, "transitionId", 128);
        String requestId = required(rawRequestId, "requestId", 64);
        String checksum = operationChecksum("FLOW_TRANSITION", flowKey,
                Map.of("transitionId", transitionId));
        OperationResult replay = beginOperation(
                requestId, "FLOW_TRANSITION", flowKey, checksum, operator);
        if (replay != null) return replay;

        Instant now = clock.instant();
        FlowInstanceRecord before = lockActive(flowKey, now);
        Snapshot snapshot = snapshot(before.releaseId());
        ReleaseSnapshotCompiler.FlowDefinition definition = snapshot.flow(
                before.providerCode(), before.flowCode(), before.flowDefinitionVersionId(),
                before.flowDefinitionChecksum());
        ReleaseSnapshotCompiler.FlowTransitionDefinition transition = definition.transitions().stream()
                .filter(value -> value.transitionId().equals(transitionId))
                .filter(value -> value.from().equals(before.currentState()))
                .filter(value -> "MANUAL".equals(value.trigger().type()))
                .findFirst()
                .orElseThrow(() -> new PlatformException(
                        ErrorCode.INVALID_STATE, "No MANUAL transition is available from the current Flow state"));
        Applied applied = apply(before, definition, transition, now);
        advance(before, applied, now);
        long eventId = insertEvent(before, transition, applied, "MANUAL", "manual:" + requestId,
                operator.operatorId(), now);
        createCallbacks(snapshot, before, applied, eventId, now);

        OperationResult result = new OperationResult(
                flowKey, before.generation(), "ACTIVE", applied.toState(), requestId);
        completeOperation(requestId, result, now);
        audit(before, result, "FLOW_TRANSITION", checksum, requestId, operator);
        return result;
    }

    @Transactional
    public OperationResult reset(
            String rawFlowKey,
            String rawRequestId,
            boolean keepPinnedVersion,
            OperatorContext operator) {
        String flowKey = required(rawFlowKey, "flowKey", 128);
        String requestId = required(rawRequestId, "requestId", 64);
        String checksum = operationChecksum("FLOW_RESET", flowKey,
                Map.of("keepPinnedVersion", keepPinnedVersion));
        OperationResult replay = beginOperation(requestId, "FLOW_RESET", flowKey, checksum, operator);
        if (replay != null) return replay;

        Instant now = clock.instant();
        FlowInstanceRecord before = lock(flowKey);
        cancelCallbacksOrFailBusy(before, "Cancelled by Flow reset", now);
        String releaseId = before.releaseId();
        if (!keepPinnedVersion) {
            ActiveReleaseRecord active = releases.selectActive(before.environment(), before.appCode());
            if (active == null || active.releaseId() == null || !"APPLIED".equals(active.state())) {
                throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                        "No applied Active Release is available for Flow reset");
            }
            releaseId = active.releaseId();
        }
        Snapshot snapshot = snapshot(releaseId);
        ReleaseSnapshotCompiler.FlowDefinition definition = snapshot.flow(
                before.providerCode(), before.flowCode(),
                keepPinnedVersion ? before.flowDefinitionVersionId() : null,
                keepPinnedVersion ? before.flowDefinitionChecksum() : null);
        Map<String, Object> variables = resetVariables(before, definition);
        Pending pending = pending(definition, definition.initialState(), now);
        int updated = mapper.reset(
                before.id(), before.version(), releaseId,
                Long.parseLong(definition.flowDefinitionVersionId()), definition.checksum(),
                definition.initialState(), json(variables), pending.transitionId(), pending.executeAt(),
                now.plusSeconds(definition.ttlSeconds()), now);
        if (updated != 1) throw conflict();
        OperationResult result = new OperationResult(
                flowKey, before.generation() + 1, "ACTIVE", definition.initialState(), requestId);
        completeOperation(requestId, result, now);
        audit(before, result, "FLOW_RESET", checksum, requestId, operator);
        return result;
    }

    @Transactional
    public OperationResult delete(String rawFlowKey, String rawRequestId, OperatorContext operator) {
        String flowKey = required(rawFlowKey, "flowKey", 128);
        String requestId = required(rawRequestId, "requestId", 64);
        String checksum = operationChecksum("FLOW_DELETE", flowKey, Map.of());
        OperationResult replay = beginOperation(requestId, "FLOW_DELETE", flowKey, checksum, operator);
        if (replay != null) return replay;

        Instant now = clock.instant();
        FlowInstanceRecord before = lock(flowKey);
        if (!"DELETED".equals(before.status())) {
            cancelCallbacksOrFailBusy(before, "Cancelled by Flow delete", now);
            if (mapper.delete(before.id(), before.version(), now) != 1) throw conflict();
        }
        OperationResult result = new OperationResult(
                flowKey,
                "DELETED".equals(before.status()) ? before.generation() : before.generation() + 1,
                "DELETED", before.currentState(), requestId);
        completeOperation(requestId, result, now);
        audit(before, result, "FLOW_DELETE", checksum, requestId, operator);
        return result;
    }

    /** Executes one due timer transition in one short transaction. */
    @Transactional
    public boolean advanceOneDueTimer() {
        Instant now = clock.instant();
        FlowInstanceRecord before = mapper.lockNextDue(now);
        if (before == null) return false;
        if (!before.expireAt().isAfter(now)) {
            if (mapper.expire(before.id(), before.version(), now) != 1) throw conflict();
            return true;
        }
        Snapshot snapshot = snapshot(before.releaseId());
        ReleaseSnapshotCompiler.FlowDefinition definition = snapshot.flow(
                before.providerCode(), before.flowCode(), before.flowDefinitionVersionId(),
                before.flowDefinitionChecksum());
        ReleaseSnapshotCompiler.FlowTransitionDefinition transition = definition.transitions().stream()
                .filter(value -> value.transitionId().equals(before.pendingTransitionId()))
                .filter(value -> value.from().equals(before.currentState()))
                .filter(value -> "TIME".equals(value.trigger().type()))
                .findFirst()
                .orElseThrow(() -> new PlatformException(ErrorCode.INVALID_STATE,
                        "Pending TIME transition is inconsistent with the fixed Flow definition"));
        Applied applied = apply(before, definition, transition, now);
        advance(before, applied, now);
        String internalId = "timer:" + before.id() + ':' + before.generation() + ':' + transition.transitionId();
        long eventId = insertEvent(before, transition, applied, "TIMER", internalId, null, now);
        createCallbacks(snapshot, before, applied, eventId, now);
        return true;
    }

    private void advance(FlowInstanceRecord before, Applied applied, Instant now) {
        if (mapper.advance(before.id(), before.version(), before.generation(), applied.toState(),
                json(applied.variables()), applied.pending().transitionId(),
                applied.pending().executeAt(), now) != 1) throw conflict();
    }

    private long insertEvent(
            FlowInstanceRecord flow,
            ReleaseSnapshotCompiler.FlowTransitionDefinition transition,
            Applied applied,
            String source,
            String internalId,
            String operator,
            Instant now) {
        String stable = source + '|' + flow.id() + '|' + flow.generation() + '|'
                + internalId + '|' + transition.transitionId();
        String eventUuid = UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)).toString();
        mapper.insertEvent(eventUuid, flow.id(), flow.generation(), source, internalId,
                transition.transitionId(), flow.currentState(), applied.toState(),
                "TIMER".equals(source) ? "TIME" : "MANUAL", flow.queryCount(), operator, now);
        Long id = mapper.selectEventId(eventUuid);
        if (id == null) throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Flow Event insert returned no id");
        return id;
    }

    private Applied apply(
            FlowInstanceRecord flow,
            ReleaseSnapshotCompiler.FlowDefinition definition,
            ReleaseSnapshotCompiler.FlowTransitionDefinition transition,
            Instant now) {
        Map<String, Object> values = map(flow.variablesJson());
        for (ReleaseSnapshotCompiler.FlowAssignmentDefinition assignment : transition.assignments()) {
            switch (assignment.type()) {
                case "SET_LITERAL" -> values.put(assignment.variable(), scalar(assignment.value()));
                case "INCREMENT_NUMBER" -> {
                    BigDecimal current = decimal(values.get(assignment.variable()));
                    BigDecimal increment = decimal(assignment.increment());
                    values.put(assignment.variable(), current.add(increment));
                }
                case "CLEAR" -> values.remove(assignment.variable());
                case "SET_FROM_REQUEST_FIELD" -> throw new PlatformException(
                        ErrorCode.INVALID_STATE, "TIME/MANUAL transition cannot read a request field");
                default -> throw new PlatformException(ErrorCode.INVALID_STATE,
                        "Stored Flow assignment type is unsupported");
            }
        }
        normalizeVariables(definition, values);
        Pending pending = pending(definition, transition.to(), now);
        return new Applied(transition.to(), Map.copyOf(values), pending);
    }

    private Map<String, Object> resetVariables(
            FlowInstanceRecord before,
            ReleaseSnapshotCompiler.FlowDefinition definition) {
        Map<String, Object> old = map(before.variablesJson());
        Map<String, Object> values = new LinkedHashMap<>();
        for (ReleaseSnapshotCompiler.FlowVariableDefinition variable : definition.variables()) {
            JsonNode literal = variable.initialValue() == null ? null : variable.initialValue().value();
            if (variable.initialValue() != null && "LITERAL".equals(variable.initialValue().source())
                    && literal != null && !literal.isNull()) {
                values.put(variable.name(), scalar(literal));
            } else if (old.containsKey(variable.name())) {
                values.put(variable.name(), old.get(variable.name()));
            }
        }
        normalizeVariables(definition, values);
        return Map.copyOf(values);
    }

    private void normalizeVariables(
            ReleaseSnapshotCompiler.FlowDefinition definition,
            Map<String, Object> values) {
        Set<String> allowed = new HashSet<>();
        for (ReleaseSnapshotCompiler.FlowVariableDefinition variable : definition.variables()) {
            allowed.add(variable.name());
            Object value = values.get(variable.name());
            if (value == null) {
                if (variable.required()) throw new PlatformException(
                        ErrorCode.INVALID_STATE, "Required Flow variable is unavailable: " + variable.name());
                continue;
            }
            Object normalized = switch (variable.type()) {
                case "STRING" -> String.valueOf(value);
                case "NUMBER" -> decimal(value);
                case "BOOLEAN" -> bool(value);
                default -> throw new PlatformException(ErrorCode.INVALID_STATE, "Stored Flow variable type is invalid");
            };
            if (normalized instanceof String text && variable.maxLength() != null
                    && text.length() > variable.maxLength()) {
                throw new PlatformException(ErrorCode.INVALID_STATE, "Flow variable exceeds maxLength");
            }
            values.put(variable.name(), normalized);
        }
        values.keySet().removeIf(name -> !allowed.contains(name));
        if (jsonBytes(values).length > 8 * 1024) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Flow variables exceed 8KB");
        }
    }

    private Pending pending(
            ReleaseSnapshotCompiler.FlowDefinition definition,
            String state,
            Instant enteredAt) {
        return definition.transitions().stream()
                .filter(value -> state.equals(value.from()))
                .filter(value -> "TIME".equals(value.trigger().type()))
                .sorted(Comparator.comparingInt(ReleaseSnapshotCompiler.FlowTransitionDefinition::priority)
                        .reversed().thenComparing(ReleaseSnapshotCompiler.FlowTransitionDefinition::transitionId))
                .findFirst()
                .map(value -> new Pending(value.transitionId(),
                        enteredAt.plusSeconds(value.trigger().delaySeconds())))
                .orElse(new Pending(null, null));
    }

    private void createCallbacks(
            Snapshot snapshot,
            FlowInstanceRecord flow,
            Applied applied,
            long eventId,
            Instant now) {
        Set<String> callbackIds = new HashSet<>();
        snapshot.value().compiledScenarios().stream()
                .filter(scenario -> String.valueOf(flow.flowDefinitionVersionId())
                        .equals(scenario.flowDefinitionVersionId()))
                .sorted(Comparator.comparing(ReleaseSnapshotCompiler.ScenarioDefinition::provider)
                        .thenComparing(ReleaseSnapshotCompiler.ScenarioDefinition::api)
                        .thenComparing(ReleaseSnapshotCompiler.ScenarioDefinition::scenarioVersionId))
                .forEach(scenario -> scenario.callbacks().stream()
                        .filter(ReleaseSnapshotCompiler.CallbackDefinition::enabled)
                        .filter(callback -> applied.toState().equals(callback.triggerState()))
                        .filter(callback -> callbackIds.add(callback.callbackDefinitionId()))
                        .forEach(callback -> insertCallbacks(
                                snapshot, scenario, callback, flow, applied, eventId, now)));
    }

    private void insertCallbacks(
            Snapshot snapshot,
            ReleaseSnapshotCompiler.ScenarioDefinition scenario,
            ReleaseSnapshotCompiler.CallbackDefinition callback,
            FlowInstanceRecord flow,
            Applied applied,
            long eventId,
            Instant now) {
        if (!"FIXED".equals(callback.urlSource()) || callback.url() == null) {
            throw new PlatformException(ErrorCode.INVALID_STATE,
                    "TIME/MANUAL callback requires a fixed URL because no SDK request is available");
        }
        byte[] payload = render(callback.payloadTemplate(), flow, applied).getBytes(StandardCharsets.UTF_8);
        CallbackTaskPayloadCodec.ProtectedValue url = callbackCodec.encrypt(
                callback.url().getBytes(StandardCharsets.UTF_8));
        CallbackTaskPayloadCodec.ProtectedValue headers = callbackCodec.encrypt(jsonBytes(callback.headers()));
        CallbackTaskPayloadCodec.ProtectedValue body = callbackCodec.encrypt(payload);
        if (!url.keyId().equals(headers.keyId()) || !url.keyId().equals(body.keyId())) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Callback encryption key changed during transaction");
        }
        for (int index = 0; index < callback.totalDeliveryCount(); index++) {
            String stable = eventId + "|" + callback.callbackDefinitionId() + "|" + index;
            String deliveryId = UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)).toString();
            String taskId = UUID.nameUUIDFromBytes(("task|" + stable).getBytes(StandardCharsets.UTF_8)).toString();
            mapper.insertCallback(
                    taskId, deliveryId, eventId, flow.id(), flow.generation(), flow.releaseId(),
                    snapshot.checksum(), callback.callbackDefinitionId(), index,
                    scenario.provider(), scenario.api(), url.ciphertext(), callback.method(),
                    headers.ciphertext(), body.ciphertext(), Checksum.sha256Hex(payload), url.keyId(),
                    callback.signaturePolicyVersionId(), callback.allowlistPolicyVersionId(),
                    now.plusMillis(callback.delayMs() + callback.deliveryOffsetsMs().get(index)),
                    callback.maxRetry(), json(callback.retryIntervalsMs()), flow.expireAt(), now);
        }
    }

    private String render(String template, FlowInstanceRecord flow, Applied applied) {
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value;
            if ("state.current".equals(name)) value = applied.toState();
            else if (name.startsWith("flow.variable.")) value = applied.variables().get(name.substring(14));
            else throw new PlatformException(ErrorCode.INVALID_STATE,
                    "TIME/MANUAL callback template requires unavailable context: " + name);
            if (value == null) throw new PlatformException(ErrorCode.INVALID_STATE,
                    "Callback template variable is missing: " + name);
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Snapshot snapshot(String releaseId) {
        ReleaseRecord release = releases.selectRelease(releaseId);
        if (release == null || release.snapshotJson() == null) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Fixed Release Snapshot is unavailable");
        }
        snapshotVerifier.verify(release.snapshotBytes(), release.id(), release.checksum());
        try {
            JsonNode envelope = objectMapper.readTree(release.snapshotJson());
            ReleaseSnapshotCompiler.PublishedSnapshot value = objectMapper.treeToValue(
                    envelope.path("snapshot"), ReleaseSnapshotCompiler.PublishedSnapshot.class);
            return new Snapshot(value, release.checksum());
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                    "Fixed Release Snapshot is invalid", failure);
        }
    }

    private void cancelCallbacksOrFailBusy(FlowInstanceRecord flow, String reason, Instant now) {
        List<FlowCallbackState> tasks = mapper.lockPendingCallbacks(flow.id(), flow.generation());
        if (tasks.stream().anyMatch(task -> "RUNNING".equals(task.status()))) {
            throw new PlatformException(ErrorCode.MOCK_FLOW_OPERATION_BUSY,
                    "A Callback Task is RUNNING for this Flow generation");
        }
        mapper.cancelPendingCallbacks(flow.id(), flow.generation(), reason, now);
    }

    private OperationResult beginOperation(
            String requestId,
            String operation,
            String flowKey,
            String checksum,
            OperatorContext operator) {
        mapper.insertAdminOperation(requestId, operation, flowKey, checksum, operator.operatorId());
        CallbackAdminOperationRecord record = mapper.lockAdminOperation(requestId);
        if (record == null || !operation.equals(record.operationType())
                || !"FLOW_INSTANCE".equals(record.resourceType())
                || !flowKey.equals(record.resourceId()) || !checksum.equals(record.requestChecksum())) {
            throw new PlatformException(ErrorCode.MOCK_IDEMPOTENCY_CONFLICT,
                    "Request ID was already used for a different Flow operation");
        }
        if (!"COMPLETED".equals(record.status())) return null;
        try {
            return objectMapper.readValue(record.resultJson(), OperationResult.class);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Flow operation result is invalid", failure);
        }
    }

    private void completeOperation(String requestId, OperationResult result, Instant now) {
        if (mapper.completeAdminOperation(requestId, json(result), now) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Flow operation completion was fenced");
        }
    }

    private void audit(
            FlowInstanceRecord before,
            OperationResult result,
            String action,
            String checksum,
            String requestId,
            OperatorContext operator) {
        audit.record(new OperatorContext(operator.operatorId(), operator.roles(), requestId),
                action, "FLOW_INSTANCE", before.flowKey(), checksum,
                Map.of("generation", before.generation(), "status", before.status(), "state", before.currentState()),
                Map.of("generation", result.generation(), "status", result.status(), "state", result.currentState()));
    }

    private FlowInstanceRecord lockActive(String flowKey, Instant now) {
        FlowInstanceRecord value = lock(flowKey);
        if (!"ACTIVE".equals(value.status()) || !value.expireAt().isAfter(now)) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Flow is not active");
        }
        return value;
    }

    private FlowInstanceRecord lock(String flowKey) {
        FlowInstanceRecord value = mapper.lockByKey(flowKey);
        if (value == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Flow Instance not found");
        return value;
    }

    private FlowInstanceRecord require(String rawFlowKey) {
        FlowInstanceRecord value = mapper.selectByKey(required(rawFlowKey, "flowKey", 128));
        if (value == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Flow Instance not found");
        return value;
    }

    private FlowInstanceView view(FlowInstanceRecord value) {
        return new FlowInstanceView(
                value.id(), value.flowKey(), value.environment(), value.appCode(), value.providerCode(),
                value.flowCode(), value.tenantCode(), value.testAccount(), value.businessNoMasked(),
                value.releaseId(), value.flowDefinitionVersionId(), value.flowDefinitionChecksum(),
                value.generation(), value.status(), value.currentState(), value.queryCount(),
                tree(value.variablesJson()), value.version(), value.pendingTransitionId(),
                value.nextTransitionAt(), value.expireAt(), value.createdAt(), value.updatedAt());
    }

    private String operationChecksum(String operation, String flowKey, Map<String, Object> input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("operation", operation);
        value.put("flowKey", flowKey);
        value.putAll(input);
        return Checksum.sha256Hex(CanonicalJson.write(value));
    }

    private JsonNode tree(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Flow variables are invalid", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String value) {
        try { return objectMapper.readValue(value, LinkedHashMap.class); }
        catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Flow variables are invalid", failure);
        }
    }

    private Object scalar(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.decimalValue();
        throw new PlatformException(ErrorCode.INVALID_STATE, "Flow assignment literal must be scalar");
    }

    private BigDecimal decimal(Object value) {
        try { return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value)); }
        catch (RuntimeException failure) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Flow NUMBER variable is invalid", failure);
        }
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        throw new PlatformException(ErrorCode.INVALID_STATE, "Flow BOOLEAN variable is invalid");
    }

    private byte[] jsonBytes(Object value) {
        try { return objectMapper.writeValueAsBytes(value); }
        catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Value cannot be serialized", failure);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Value cannot be serialized", failure);
        }
    }

    static String optional(String value, String field, int max) {
        return value == null || value.isBlank() ? null : required(value, field, max);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(field + " is invalid");
        return value.trim();
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(ErrorCode.INVALID_REQUEST, message);
    }

    private static PlatformException conflict() {
        return new PlatformException(ErrorCode.MOCK_FLOW_CONFLICT, "Flow version changed concurrently");
    }

    private record Applied(String toState, Map<String, Object> variables, Pending pending) { }
    private record Pending(String transitionId, Instant executeAt) { }
    private record Snapshot(ReleaseSnapshotCompiler.PublishedSnapshot value, String checksum) {
        ReleaseSnapshotCompiler.FlowDefinition flow(
                String provider,
                String flowCode,
                Long expectedVersionId,
                String expectedChecksum) {
            return value.flowDefinitions().stream()
                    .filter(definition -> provider.equals(definition.provider()))
                    .filter(definition -> flowCode.equals(definition.flowCode()))
                    .filter(definition -> expectedVersionId == null
                            || String.valueOf(expectedVersionId).equals(definition.flowDefinitionVersionId()))
                    .filter(definition -> expectedChecksum == null || expectedChecksum.equals(definition.checksum()))
                    .findFirst()
                    .orElseThrow(() -> new PlatformException(
                            ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                            "Flow Definition is unavailable in the selected Release Snapshot"));
        }
    }

    public record FlowInstanceView(
            long id,
            String flowKey,
            String environment,
            String appCode,
            String providerCode,
            String flowCode,
            String tenantCode,
            String testAccount,
            String businessNoMasked,
            String releaseId,
            long flowDefinitionVersionId,
            String flowDefinitionChecksum,
            int generation,
            String status,
            String currentState,
            long queryCount,
            JsonNode variables,
            long version,
            String pendingTransitionId,
            Instant nextTransitionAt,
            Instant expireAt,
            Instant createdAt,
            Instant updatedAt) { }

    public record OperationResult(
            String flowKey,
            int generation,
            String status,
            String currentState,
            String requestId) { }
}
