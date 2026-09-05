package com.xuntian.mock.control.securitypolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
import com.xuntian.mock.control.web.AdminWriteRequestGuard;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1")
public final class SecurityPolicyAdminController {

    private final SecurityPolicyService service;
    private final OperatorGuard operatorGuard;
    private final AdminWriteRequestGuard writeRequestGuard;

    public SecurityPolicyAdminController(
            SecurityPolicyService service,
            OperatorGuard operatorGuard,
            AdminWriteRequestGuard writeRequestGuard) {
        this.service = service;
        this.operatorGuard = operatorGuard;
        this.writeRequestGuard = writeRequestGuard;
    }

    @GetMapping("/security-policies")
    public ApiResponse<List<SecurityPolicyService.PolicySummary>> findPolicies(
            @RequestParam(required = false) String policyType,
            @RequestParam(required = false) String scopeKey,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.findPolicies(policyType, scopeKey), PlatformController.requestId(request));
    }

    @GetMapping("/security-policies/{policyId}/versions")
    public ApiResponse<List<SecurityPolicyService.PolicyVersionView>> findVersions(
            @PathVariable String policyId,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.findVersions(policyId), PlatformController.requestId(request));
    }

    @PostMapping("/security-policies")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SecurityPolicyService.PolicyVersionView> create(
            @RequestBody CreateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(
                service.create(null, command(body), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/security-policies/{policyId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SecurityPolicyService.PolicyVersionView> createVersion(
            @PathVariable String policyId,
            @RequestBody CreateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(
                service.create(policyId, command(body), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/security-policy-versions/{id}/validate")
    public ApiResponse<SecurityPolicyService.PolicyVersionView> validate(
            @PathVariable long id,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(service.validate(id, operator), PlatformController.requestId(request));
    }

    @PostMapping("/security-policy-versions/{id}/submit-approval")
    public ApiResponse<ApprovalService.ApprovalView> submitApproval(
            @PathVariable long id,
            @RequestBody SubmitApprovalRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(
                service.submitApproval(id, body.policyCode(), body.requiredCount(), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/security-policy-versions/{id}/publish")
    public ApiResponse<SecurityPolicyService.PublishView> publish(
            @PathVariable long id,
            @RequestBody PublishRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(
                service.publish(id, body.expectedBindingVersion(), operator),
                PlatformController.requestId(request));
    }

    @GetMapping("/security-policy-versions/{id}/diff")
    public ApiResponse<SecurityPolicyService.PolicyDiff> diff(
            @PathVariable long id,
            @RequestParam long compareTo,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.diff(id, compareTo), PlatformController.requestId(request));
    }

    @GetMapping("/security-policy-bindings")
    public ApiResponse<SecurityPolicyService.BindingView> binding(
            @RequestParam String policyType,
            @RequestParam String scopeKey,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.findBinding(policyType, scopeKey), PlatformController.requestId(request));
    }

    private SecurityPolicyService.CreateCommand command(CreateRequest body) {
        return new SecurityPolicyService.CreateCommand(
                body.policyType(), body.scopeKey(), body.config(), body.sourceAuditRef());
    }

    public record CreateRequest(
            String policyType,
            String scopeKey,
            JsonNode config,
            String sourceAuditRef) {
    }

    public record SubmitApprovalRequest(String policyCode, int requiredCount) {
    }

    public record PublishRequest(long expectedBindingVersion) {
    }
}
