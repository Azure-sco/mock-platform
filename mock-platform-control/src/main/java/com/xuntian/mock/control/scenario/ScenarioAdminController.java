package com.xuntian.mock.control.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/v1")
public final class ScenarioAdminController {

    private final ScenarioService scenarioService;
    private final OperatorGuard operatorGuard;

    public ScenarioAdminController(ScenarioService scenarioService, OperatorGuard operatorGuard) {
        this.scenarioService = scenarioService;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping("/scenarios")
    public ApiResponse<List<ScenarioService.ScenarioSummary>> findAll(HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(scenarioService.findAll(), PlatformController.requestId(request));
    }

    @GetMapping("/scenarios/{id}")
    public ApiResponse<ScenarioService.ScenarioView> find(@PathVariable long id, HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(scenarioService.find(id), PlatformController.requestId(request));
    }

    @PostMapping("/scenarios")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ScenarioService.ScenarioSummary> create(
            @RequestBody CreateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(scenarioService.create(
                        new ScenarioService.CreateCommand(
                                body.scenarioCode(), body.scenarioName(), body.providerId(), body.apiId()), operator),
                PlatformController.requestId(request));
    }

    @PutMapping("/scenarios/{id}")
    public ApiResponse<ScenarioService.ScenarioSummary> update(
            @PathVariable long id,
            @RequestBody UpdateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(scenarioService.update(
                        id, new ScenarioService.UpdateCommand(body.scenarioName(), body.status()), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/scenarios/{id}/disable")
    public ApiResponse<ScenarioService.ScenarioSummary> disable(
            @PathVariable long id,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(scenarioService.disable(id, operator), PlatformController.requestId(request));
    }

    @PostMapping("/scenarios/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ScenarioService.ScenarioVersionView> createVersion(
            @PathVariable long id,
            @RequestBody VersionRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(scenarioService.createVersion(id, body.command(), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/scenario-versions/{id}/validate")
    public ApiResponse<ScenarioService.ScenarioVersionView> validate(
            @PathVariable long id,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(scenarioService.validate(id, operator), PlatformController.requestId(request));
    }

    @PostMapping("/scenario-versions/{id}/submit-approval")
    public ApiResponse<ApprovalService.ApprovalView> submitApproval(
            @PathVariable long id,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(scenarioService.submitApproval(id, operator), PlatformController.requestId(request));
    }

    @PostMapping("/scenario-versions/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ScenarioService.ScenarioView> copy(
            @PathVariable long id,
            @RequestBody CopyRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(scenarioService.copy(
                        id, new ScenarioService.CopyCommand(body.scenarioCode(), body.scenarioName()), operator),
                PlatformController.requestId(request));
    }

    public record CreateRequest(String scenarioCode, String scenarioName, long providerId, long apiId) {
    }

    public record UpdateRequest(String scenarioName, String status) {
    }

    public record VersionRequest(
            long contractVersionId,
            Long flowDefinitionVersionId,
            int priority,
            Instant effectiveFrom,
            Instant effectiveTo,
            JsonNode scope,
            JsonNode matchRules,
            JsonNode response,
            JsonNode callbacks) {

        ScenarioService.CreateVersionCommand command() {
            return new ScenarioService.CreateVersionCommand(
                    contractVersionId, flowDefinitionVersionId, priority, effectiveFrom, effectiveTo,
                    scope, matchRules, response, callbacks);
        }
    }

    public record CopyRequest(String scenarioCode, String scenarioName) {
    }
}
