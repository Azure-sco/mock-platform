package com.xuntian.mock.control.flow;

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

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1")
public final class FlowDefinitionAdminController {

    private final FlowDefinitionService service;
    private final OperatorGuard operatorGuard;

    public FlowDefinitionAdminController(FlowDefinitionService service, OperatorGuard operatorGuard) {
        this.service = service;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping("/flow-definitions")
    public ApiResponse<List<FlowDefinitionService.FlowDefinitionSummary>> findAll(HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(service.findAll(), PlatformController.requestId(request));
    }

    @GetMapping("/flow-definitions/{id}")
    public ApiResponse<FlowDefinitionService.FlowDefinitionView> find(
            @PathVariable long id,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(service.find(id), PlatformController.requestId(request));
    }

    @PostMapping("/flow-definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FlowDefinitionService.FlowDefinitionSummary> create(
            @RequestBody CreateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(service.create(
                new FlowDefinitionService.CreateCommand(body.providerId(), body.flowCode(), body.flowName()),
                operator), PlatformController.requestId(request));
    }

    @PutMapping("/flow-definitions/{id}")
    public ApiResponse<FlowDefinitionService.FlowDefinitionSummary> update(
            @PathVariable long id,
            @RequestBody UpdateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(service.update(
                id, new FlowDefinitionService.UpdateCommand(body.flowName(), body.status()), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/flow-definitions/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FlowDefinitionService.FlowDefinitionVersionView> createVersion(
            @PathVariable long id,
            @RequestBody VersionRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(service.createVersion(id, new FlowDefinitionService.CreateVersionCommand(
                body.initialState(), body.ttlSeconds(), body.participantApis(), body.variables(), body.transitions()),
                operator), PlatformController.requestId(request));
    }

    @PostMapping("/flow-definition-versions/{id}/validate")
    public ApiResponse<FlowDefinitionService.FlowDefinitionVersionView> validate(
            @PathVariable long id,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(service.validate(id, operator), PlatformController.requestId(request));
    }

    @PostMapping("/flow-definition-versions/{id}/submit-approval")
    public ApiResponse<ApprovalService.ApprovalView> submitApproval(
            @PathVariable long id,
            @RequestBody(required = false) ApprovalRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        String policy = body == null ? "FLOW_DUAL_CONTROL" : body.policyCode();
        int required = body == null ? 2 : body.requiredCount();
        return ApiResponse.success(service.submitApproval(id, policy, required, operator),
                PlatformController.requestId(request));
    }

    public record CreateRequest(long providerId, String flowCode, String flowName) { }

    public record UpdateRequest(String flowName, String status) { }

    public record VersionRequest(
            String initialState,
            long ttlSeconds,
            JsonNode participantApis,
            JsonNode variables,
            JsonNode transitions) { }

    public record ApprovalRequest(String policyCode, int requiredCount) { }
}
