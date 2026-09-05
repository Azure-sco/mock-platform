package com.xuntian.mock.runtime.flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.callback.CompiledCallbackDefinition;
import com.xuntian.mock.runtime.engine.ApiKey;
import com.xuntian.mock.runtime.engine.CompiledApi;
import com.xuntian.mock.runtime.engine.CompiledContract;
import com.xuntian.mock.runtime.engine.CompiledScenario;
import com.xuntian.mock.runtime.engine.ContractScenarioEngine;
import com.xuntian.mock.runtime.engine.RuntimeExecution;
import com.xuntian.mock.runtime.engine.RuntimeFault;
import com.xuntian.mock.runtime.engine.RuntimeRequest;
import com.xuntian.mock.runtime.engine.TemplateContext;
import com.xuntian.mock.runtime.release.PinnedRuntimeSnapshot;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshot;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshotRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("!test")
public final class RuntimeFlowExecutionService implements RuntimeRequestExecutor {

    private static final Duration EXECUTION_TTL = Duration.ofHours(24);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final ContractScenarioEngine engine;
    private final FlowTransitionService transitions;
    private final JdbcFlowStore store;
    private final RuntimeCryptography cryptography;
    private final RuntimeSnapshotRepository snapshots;
    private final TransactionTemplate transaction;
    private final Scheduler scheduler;
    private final ObjectMapper mapper;

    public RuntimeFlowExecutionService(
            ContractScenarioEngine engine,
            JdbcFlowStore store,
            RuntimeCryptography cryptography,
            RuntimeSnapshotRepository snapshots,
            TransactionTemplate transaction,
            @Qualifier("runtimeJdbcScheduler") Scheduler scheduler,
            ObjectMapper mapper) {
        this.engine = engine;
        this.transitions = new FlowTransitionService(mapper);
        this.store = store;
        this.cryptography = cryptography;
        this.snapshots = snapshots;
        this.transaction = transaction;
        this.scheduler = scheduler;
        this.mapper = mapper.copy();
    }

    @Override
    public Mono<ExecutedResponse> execute(
            PinnedRuntimeSnapshot pinned,
            RuntimeRequest request,
            Instant requestTime,
            UUID requestUuid) {
        RuntimeSnapshot snapshot = pinned.snapshot();
        FlowLocator locator = snapshot.flowLocators().get(new ApiKey(request.provider(), request.api()));
        if (locator == null) {
            RuntimeExecution decision = engine.execute(snapshot, request, requestTime, requestUuid);
            if (!decision.fault().enabled()) {
                return Mono.just(new ExecutedResponse(decision, pinned.activationVersion(), false));
            }
            return Mono.fromCallable(() -> transaction.execute(status -> executeStatelessFault(
                            pinned, request, requestTime, decision)))
                    .subscribeOn(scheduler);
        }
        return Mono.fromCallable(() -> transaction.execute(status -> executeTransaction(
                        pinned, request, requestTime, requestUuid, locator)))
                .subscribeOn(scheduler);
    }

    private ExecutedResponse executeTransaction(
            PinnedRuntimeSnapshot pinned,
            RuntimeRequest request,
            Instant now,
            UUID requestUuid,
            FlowLocator currentLocator) {
        String fingerprint = RequestFingerprint.calculate(request);
        JdbcFlowStore.RequestExecutionRecord ledger = store.lockExecution(
                request.app(), request.mockRequestId(), fingerprint, now, now.plus(EXECUTION_TTL));
        if (ledger.completed()) return replay(ledger);

        CompiledContract.ContractMatch contract = engine.validateContract(pinned.snapshot(), request);
        JsonNode body = contract.body();
        String businessNo = currentLocator.participant().businessKeyExtractor().extract(request, body);
        String tenant = blank(request.tenant());
        String testAccount = blank(request.testAccount());
        JdbcFlowStore.RuntimeFlowScope scope = new JdbcFlowStore.RuntimeFlowScope(
                request.environment(), request.app(), request.provider(),
                currentLocator.definition().flowCode(), tenant, testAccount);
        String hmacScope = String.join("\u0000", scope.environment(), scope.app(), scope.provider(),
                scope.flowCode(), scope.tenant(), scope.testAccount());
        List<FlowTransitionService.FlowKey> keys = cryptography.flowKeys(hmacScope, businessNo);
        FlowInstance before = store.findFlowForUpdate(scope, keys);
        boolean activeBefore = before != null && before.status() == FlowInstance.Status.ACTIVE
                && before.expireAt().isAfter(now);
        PinnedRuntimeSnapshot decisionPinned = pinned;
        CompiledFlowDefinition decisionDefinition = currentLocator.definition();
        CompiledFlowDefinition.Participant decisionParticipant = currentLocator.participant();
        if (activeBefore) {
            decisionPinned = snapshots.requirePinnedRelease(
                    request.environment(), request.app(), before.releaseId());
            decisionDefinition = decisionPinned.snapshot().flowDefinitions()
                    .get(before.flowDefinitionVersionId());
            if (decisionDefinition == null
                    || !decisionDefinition.checksum().equals(before.flowDefinitionChecksum())) {
                throw new PlatformException(
                        ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                        "The fixed Flow Definition Version is not available in the pinned Snapshot");
            }
            decisionParticipant = decisionDefinition.requireParticipant(request.api());
        } else if (!mayCreate(decisionParticipant)) {
            throw new PlatformException(ErrorCode.MOCK_FLOW_CONFLICT,
                    "Missing, expired or deleted Flow requires a CREATE Participant");
        }
        CompiledScenario faultDecision = engine.selectScenario(
                decisionPinned.snapshot(), request, now,
                decisionDefinition.flowDefinitionVersionId(), decisionDefinition.checksum(), activeBefore);
        if (faultDecision.fault().sideEffectPolicy() == RuntimeFault.SideEffectPolicy.NO_APPLY) {
            Map<String, Object> variables = activeBefore
                    ? before.variables()
                    : decisionDefinition.initializeVariables(request, body, mapper);
            String state = activeBefore ? before.currentState() : decisionDefinition.initialState();
            RuntimeExecution execution = engine.executeWithFlow(
                    decisionPinned.snapshot(), request, now, requestUuid, variables, state,
                    decisionDefinition.flowDefinitionVersionId(), decisionDefinition.checksum(), activeBefore);
            return completeDecision(
                    ledger, decisionPinned, execution,
                    activeBefore ? before.id() : null,
                    activeBefore ? before.generation() : null,
                    state, now);
        }
        boolean created = false;
        List<FlowEvent> lifecycleEvents = new ArrayList<>();
        CompiledFlowDefinition definition;
        CompiledFlowDefinition.Participant participant;
        PinnedRuntimeSnapshot executionPinned = pinned;
        if (before == null) {
            participant = currentLocator.participant();
            definition = currentLocator.definition();
            if (!mayCreate(participant)) {
                throw new PlatformException(ErrorCode.MOCK_FLOW_CONFLICT, "Flow does not exist for this business key");
            }
            FlowInstance proposed = transitions.create(
                    0, keys.get(0), pinned.releaseId(), definition, request, body, now);
            JdbcFlowStore.InsertOrLockResult insertion = store.insertOrLock(proposed);
            before = insertion.instance();
            created = insertion.inserted();
        }

        if (before.status() != FlowInstance.Status.ACTIVE || !before.expireAt().isAfter(now)) {
            definition = currentLocator.definition();
            participant = currentLocator.participant();
            if (!mayCreate(participant)) {
                throw new PlatformException(ErrorCode.MOCK_FLOW_CONFLICT, "Expired or deleted Flow requires CREATE");
            }
            store.cancelPendingCallbacksOrFailBusy(before.id(), before.generation(), now);
            Map<String, Object> initial = definition.initializeVariables(request, body, mapper);
            FlowInstance reactivated = before.reactivate(
                    definition, pinned.releaseId(), keys.get(0).flowKey(), keys.get(0).businessNoHmac(),
                    keys.get(0).hmacKeyVersion(), keys.get(0).businessNoMasked(), initial, now);
            store.updateFlow(before, reactivated);
            lifecycleEvents.add(transitions.reactivateEvent(
                    before, reactivated, request, ledger.generation(), now));
            before = reactivated;
            created = true;
        } else {
            executionPinned = snapshots.requirePinnedRelease(
                    request.environment(), request.app(), before.releaseId());
            definition = executionPinned.snapshot().flowDefinitions().get(before.flowDefinitionVersionId());
            if (definition == null || !definition.checksum().equals(before.flowDefinitionChecksum())) {
                throw new PlatformException(
                        ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                        "The fixed Flow Definition Version is not available in the pinned Snapshot");
            }
            participant = definition.requireParticipant(request.api());
        }

        FlowTransitionService.Result transition = transitions.applySdk(
                before, definition, participant, request, body, ledger.generation(), now);
        FlowInstance after = transition.instance();
        store.updateFlow(before, after);
        lifecycleEvents.addAll(transition.events());
        List<FlowEvent> events = lifecycleEvents.stream().map(store::insertEvent).toList();

        RuntimeExecution execution = engine.executeWithFlow(
                executionPinned.snapshot(), request, now, requestUuid, after.variables(), after.currentState(),
                definition.flowDefinitionVersionId(), definition.checksum(), !created);
        CompiledScenario selected = selectedScenario(
                executionPinned.snapshot(), request, execution.scenarioVersionId());
        createCallbacks(executionPinned, request, requestUuid, body, execution.businessNo(),
                after, events, selected, now);

        return completeDecision(
                ledger, executionPinned, execution, after.id(), after.generation(),
                after.currentState(), now);
    }

    private ExecutedResponse executeStatelessFault(
            PinnedRuntimeSnapshot pinned,
            RuntimeRequest request,
            Instant now,
            RuntimeExecution decision) {
        JdbcFlowStore.RequestExecutionRecord ledger = store.lockExecution(
                request.app(), request.mockRequestId(), RequestFingerprint.calculate(request),
                now, now.plus(EXECUTION_TTL));
        if (ledger.completed()) return replay(ledger);
        return completeDecision(ledger, pinned, decision, null, null, null, now);
    }

    private ExecutedResponse completeDecision(
            JdbcFlowStore.RequestExecutionRecord ledger,
            PinnedRuntimeSnapshot pinned,
            RuntimeExecution execution,
            Long flowInstanceId,
            Integer flowGeneration,
            String flowState,
            Instant now) {
        RuntimeCryptography.ProtectedValue encryptedHeaders = cryptography.encrypt(jsonBytes(execution.headers()));
        RuntimeCryptography.ProtectedValue encryptedBody = cryptography.encrypt(execution.body());
        Map<String, Object> resultMetadata = new LinkedHashMap<>();
        resultMetadata.put("scenarioId", execution.scenarioId());
        resultMetadata.put("businessNo", execution.businessNo());
        resultMetadata.put("flowState", flowState);
        resultMetadata.put("delayMs", execution.delayMs());
        store.completeExecution(
                ledger, pinned.releaseId(), pinned.activationVersion(),
                positiveId(execution.scenarioVersionId(), "Scenario Version"),
                flowInstanceId, flowGeneration, json(resultMetadata), execution.status(),
                encryptedHeaders, encryptedBody, execution.fault(), now);
        return new ExecutedResponse(execution, pinned.activationVersion(), false);
    }

    private void createCallbacks(
            PinnedRuntimeSnapshot pinned,
            RuntimeRequest request,
            UUID requestUuid,
            JsonNode body,
            String businessNo,
            FlowInstance flow,
            List<FlowEvent> events,
            CompiledScenario scenario,
            Instant now) {
        for (FlowEvent event : events) {
            if (event.transitionId() == null || event.fromState().equals(event.toState())) continue;
            for (CompiledCallbackDefinition callback : scenario.callbacks()) {
                if (!callback.enabled() || !event.toState().equals(callback.triggerState())) continue;
                URI url = callback.fixedUrl() != null
                        ? callback.fixedUrl()
                        : dynamicUrl(callback, body, request.environment());
                byte[] payload = callback.payloadTemplate().render(new TemplateContext(
                        request, body, businessNo, flow.variables(), flow.currentState(), now, requestUuid)).bytes();
                RuntimeCryptography.ProtectedValue encryptedUrl = cryptography.encrypt(
                        url.toASCIIString().getBytes(StandardCharsets.UTF_8));
                RuntimeCryptography.ProtectedValue encryptedHeaders = cryptography.encrypt(
                        jsonBytes(callback.headers()));
                RuntimeCryptography.ProtectedValue encryptedPayload = cryptography.encrypt(payload);
                if (!encryptedUrl.keyId().equals(encryptedHeaders.keyId())
                        || !encryptedUrl.keyId().equals(encryptedPayload.keyId())) {
                    throw new IllegalStateException("Callback encrypted fields use inconsistent key versions");
                }
                for (int index = 0; index < callback.totalDeliveryCount(); index++) {
                    String stable = event.eventId() + '|' + callback.callbackDefinitionId() + '|' + index;
                    String deliveryId = UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)).toString();
                    String taskId = UUID.nameUUIDFromBytes(("task|" + stable).getBytes(StandardCharsets.UTF_8)).toString();
                    store.insertCallback(new JdbcFlowStore.CallbackTaskInsert(
                            taskId, deliveryId, event.id(), flow.id(), flow.generation(),
                            pinned.releaseId(), pinned.snapshotChecksum(), callback.callbackDefinitionId(), index,
                            request.provider(), request.api(), encryptedUrl.ciphertext(), callback.method(),
                            encryptedHeaders.ciphertext(), encryptedPayload.ciphertext(), Checksum.sha256Hex(payload),
                            encryptedUrl.keyId(), callback.signaturePolicyVersionId(),
                            callback.allowlistPolicyVersionId(),
                            now.plusMillis(callback.delayMs() + callback.deliveryOffsetsMs().get(index)),
                            callback.maxRetry(), callback.retryIntervalsMs(), flow.expireAt(), now));
                }
            }
        }
    }

    private URI dynamicUrl(CompiledCallbackDefinition callback, JsonNode body, String environment) {
        JsonNode value = callback.requestField().read(body)
                .filter(JsonNode::isTextual)
                .orElseThrow(() -> new PlatformException(
                        ErrorCode.MOCK_TEMPLATE_VARIABLE_MISSING,
                        "Callback URL request field is missing"));
        try {
            URI uri = URI.create(value.textValue());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            boolean loopback = !"PROD".equalsIgnoreCase(environment) && "http".equals(scheme)
                    && ("localhost".equals(host) || "127.0.0.1".equals(host));
            if (!("https".equals(scheme) || loopback) || host.isBlank()
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("unsafe Callback URL");
            }
            return uri;
        } catch (IllegalArgumentException failure) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Callback URL request field is invalid", failure);
        }
    }

    private CompiledScenario selectedScenario(
            RuntimeSnapshot snapshot,
            RuntimeRequest request,
            String versionId) {
        CompiledApi api = snapshot.apis().get(new ApiKey(request.provider(), request.api()));
        return api.scenarios().stream()
                .filter(candidate -> candidate.scenarioVersionId().equals(versionId))
                .findFirst()
                .orElseThrow(() -> new PlatformException(
                        ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                        "Selected Scenario is missing from the pinned Snapshot"));
    }

    private ExecutedResponse replay(JdbcFlowStore.RequestExecutionRecord ledger) {
        try {
            Map<String, String> headers = mapper.readValue(
                    cryptography.decrypt(ledger.encryptionKeyId(), ledger.responseHeadersEncrypted()), STRING_MAP);
            JsonNode metadata = mapper.readTree(ledger.transitionResultJson());
            RuntimeExecution execution = new RuntimeExecution(
                    ledger.responseStatus(), headers,
                    cryptography.decrypt(ledger.encryptionKeyId(), ledger.responseBodyEncrypted()),
                    metadata.path("scenarioId").asText(), String.valueOf(ledger.scenarioVersionId()),
                    ledger.releaseId(), metadata.path("businessNo").isNull()
                            ? null : metadata.path("businessNo").asText(null),
                    metadata.path("delayMs").asLong(0),
                    new RuntimeFault(
                            RuntimeFault.Type.valueOf(ledger.faultType() == null ? "NONE" : ledger.faultType()),
                            ledger.faultDurationMs() == null ? 0 : ledger.faultDurationMs(),
                            RuntimeFault.SideEffectPolicy.valueOf(
                                    ledger.sideEffectPolicy() == null ? "NO_APPLY" : ledger.sideEffectPolicy())));
            return new ExecutedResponse(execution, ledger.activationVersion(), true);
        } catch (IOException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Request Execution is invalid", failure);
        }
    }

    private byte[] jsonBytes(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Value cannot be serialized", failure); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Value cannot be serialized", failure); }
    }
    private static long positiveId(String value, String name) {
        try {
            long result = Long.parseLong(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException failure) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, name + " id is invalid", failure);
        }
    }
    private static String blank(String value) { return value == null ? "" : value; }

    static boolean mayCreate(CompiledFlowDefinition.Participant participant) {
        return participant.role() == CompiledFlowDefinition.Role.CREATE
                && participant.createIfAbsent();
    }

}
