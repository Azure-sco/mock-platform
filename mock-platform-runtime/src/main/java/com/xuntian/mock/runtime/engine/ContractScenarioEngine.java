package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@Component
public final class ContractScenarioEngine {

    private final ObjectMapper mapper;

    public ContractScenarioEngine(ObjectMapper mapper) {
        this.mapper = mapper.copy();
    }

    public RuntimeExecution execute(
            RuntimeSnapshot snapshot,
            RuntimeRequest request,
            Instant requestTime,
            UUID requestUuid) {
        return execute(snapshot, request, requestTime, requestUuid, Map.of(), null, null, null, false);
    }

    public CompiledContract.ContractMatch validateContract(RuntimeSnapshot snapshot, RuntimeRequest request) {
        if (!snapshot.environment().equals(request.environment()) || !snapshot.app().equals(request.app())) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Runtime Snapshot scope is invalid");
        }
        CompiledApi api = snapshot.apis().get(new ApiKey(request.provider(), request.api()));
        if (api == null) {
            throw new PlatformException(ErrorCode.MOCK_CONTRACT_MISMATCH, "Published Contract was not found");
        }
        return api.contract().validate(request, mapper);
    }

    public CompiledScenario selectScenario(
            RuntimeSnapshot snapshot,
            RuntimeRequest request,
            Instant requestTime,
            String flowDefinitionVersionId,
            String flowDefinitionChecksum,
            boolean existingFlow) {
        if (!snapshot.environment().equals(request.environment()) || !snapshot.app().equals(request.app())) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Runtime Snapshot scope is invalid");
        }
        CompiledApi api = snapshot.apis().get(new ApiKey(request.provider(), request.api()));
        if (api == null) {
            throw new PlatformException(ErrorCode.MOCK_CONTRACT_MISMATCH, "Published Contract was not found");
        }
        CompiledContract.ContractMatch contract = api.contract().validate(request, mapper);
        List<CompiledScenario> matching = api.scenarios().stream()
                .filter(scenario -> flowBindingMatches(
                        scenario, flowDefinitionVersionId, flowDefinitionChecksum))
                .filter(scenario -> scenario.matches(request, contract, requestTime, existingFlow))
                .toList();
        return select(matching, request.firstHeader("X-Mock-Explicit-Scenario").orElse(null));
    }

    public RuntimeExecution executeWithFlow(
            RuntimeSnapshot snapshot,
            RuntimeRequest request,
            Instant requestTime,
            UUID requestUuid,
            Map<String, Object> flowVariables,
            String state,
            String flowDefinitionVersionId,
            String flowDefinitionChecksum,
            boolean existingFlow) {
        return execute(snapshot, request, requestTime, requestUuid, flowVariables, state,
                flowDefinitionVersionId, flowDefinitionChecksum, existingFlow);
    }

    private RuntimeExecution execute(
            RuntimeSnapshot snapshot,
            RuntimeRequest request,
            Instant requestTime,
            UUID requestUuid,
            Map<String, Object> flowVariables,
            String state,
            String flowDefinitionVersionId,
            String flowDefinitionChecksum,
            boolean existingFlow) {
        if (!snapshot.environment().equals(request.environment()) || !snapshot.app().equals(request.app())) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Runtime Snapshot scope is invalid");
        }
        CompiledApi api = snapshot.apis().get(new ApiKey(request.provider(), request.api()));
        if (api == null) {
            throw new PlatformException(ErrorCode.MOCK_CONTRACT_MISMATCH, "Published Contract was not found");
        }
        CompiledContract.ContractMatch contract = api.contract().validate(request, mapper);
        CompiledScenario selected = selectScenario(
                snapshot, request, requestTime, flowDefinitionVersionId,
                flowDefinitionChecksum, existingFlow);
        CompiledTemplate.RenderedTemplate rendered = selected.template().render(new TemplateContext(
                request,
                contract.body(),
                contract.businessNo(),
                flowVariables,
                state,
                requestTime,
                requestUuid));
        api.contract().validateResponse(rendered.json());
        return new RuntimeExecution(
                selected.httpStatus(),
                selected.responseHeaders(),
                rendered.bytes(),
                selected.scenarioId(),
                selected.scenarioVersionId(),
                snapshot.releaseId(),
                contract.businessNo(),
                selected.responseDelayMs(),
                selected.fault());
    }

    private boolean flowBindingMatches(
            CompiledScenario scenario,
            String flowDefinitionVersionId,
            String flowDefinitionChecksum) {
        if (flowDefinitionVersionId == null) return scenario.flowDefinitionVersionId() == null;
        return flowDefinitionVersionId.equals(scenario.flowDefinitionVersionId())
                && flowDefinitionChecksum.equals(scenario.flowDefinitionChecksum());
    }

    private CompiledScenario select(List<CompiledScenario> matching, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return matching.stream()
                    .filter(scenario -> explicit.equals(scenario.scenarioCode()))
                    .findFirst()
                    .orElseThrow(() -> noMatch("Explicit Scenario is not available in the current scope"));
        }
        return matching.stream().findFirst().orElseThrow(() -> noMatch("No published Scenario matched the request"));
    }

    private PlatformException noMatch(String message) {
        return new PlatformException(ErrorCode.MOCK_NO_MATCH, message);
    }
}
