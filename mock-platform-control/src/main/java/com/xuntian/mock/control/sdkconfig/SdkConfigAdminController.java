package com.xuntian.mock.control.sdkconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
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

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/v1")
public final class SdkConfigAdminController {

    private final SdkConfigService service;
    private final OperatorGuard operatorGuard;
    private final AdminWriteRequestGuard writeRequestGuard;

    public SdkConfigAdminController(
            SdkConfigService service,
            OperatorGuard operatorGuard,
            AdminWriteRequestGuard writeRequestGuard) {
        this.service = service;
        this.operatorGuard = operatorGuard;
        this.writeRequestGuard = writeRequestGuard;
    }

    @GetMapping("/sdk-config-envelopes")
    public ApiResponse<List<SdkConfigService.EnvelopeView>> find(
            @RequestParam("app") String appCode,
            @RequestParam("environment") String environment,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.find(appCode, environment), PlatformController.requestId(request));
    }

    @GetMapping("/sdk-config-envelopes/{id}")
    public ApiResponse<SdkConfigService.EnvelopeView> get(
            @PathVariable long id,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.get(id), PlatformController.requestId(request));
    }

    @PostMapping("/sdk-config-envelopes")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SdkConfigService.EnvelopeView> create(
            @RequestBody CreateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(service.create(new SdkConfigService.CreateCommand(
                body.appCode(), body.environment(), body.routing(), body.securityPolicyVersionIds(),
                body.effectiveAt(), body.expireAt(), body.sourceAuditRef()), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/sdk-config-envelopes/{id}/validate")
    public ApiResponse<SdkConfigService.EnvelopeView> validate(
            @PathVariable long id,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(service.validate(id, operator), PlatformController.requestId(request));
    }

    @PostMapping("/sdk-config-envelopes/{id}/submit-approval")
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

    @PostMapping("/sdk-config-envelopes/{id}/publish")
    public ApiResponse<SdkConfigTransactionService.PublishResult> publish(
            @PathVariable long id,
            @RequestBody PublishRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        String idempotencyKey = writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(service.publish(id, new SdkConfigService.PublishCommand(
                body.expectedConfigVersion(), body.targetType(), body.targetNamespace()),
                idempotencyKey, operator), PlatformController.requestId(request));
    }

    @PostMapping("/sdk-config-envelopes/{id}/rollback")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SdkConfigService.EnvelopeView> rollback(
            @PathVariable long id,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(service.rollback(id, operator), PlatformController.requestId(request));
    }

    @GetMapping("/sdk-config-envelopes/{id}/diff")
    public ApiResponse<SdkConfigService.EnvelopeDiff> diff(
            @PathVariable long id,
            @RequestParam long compareTo,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.diff(id, compareTo), PlatformController.requestId(request));
    }

    @GetMapping("/sdk-config-activations/{activationId}")
    public ApiResponse<SdkConfigTransactionService.PublishResult> activation(
            @PathVariable String activationId,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.activationView(activationId), PlatformController.requestId(request));
    }

    @PostMapping("/sdk-config-activations/{activationId}/targets/{instanceId}/waive")
    public ApiResponse<SdkConfigTransactionService.AckResult> waive(
            @PathVariable String activationId,
            @PathVariable String instanceId,
            @RequestBody WaiveRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        if (!operator.roles().contains("mock:sdk-config:override")) {
            throw new PlatformException(ErrorCode.FORBIDDEN, "mock:sdk-config:override permission is required");
        }
        writeRequestGuard.requireIdempotencyKey(request);
        return ApiResponse.success(
                service.waiveTarget(activationId, instanceId, body.reason(), operator),
                PlatformController.requestId(request));
    }

    public record CreateRequest(
            String appCode,
            String environment,
            JsonNode routing,
            List<Long> securityPolicyVersionIds,
            Instant effectiveAt,
            Instant expireAt,
            String sourceAuditRef) {
    }

    public record SubmitApprovalRequest(String policyCode, int requiredCount) {
    }

    public record PublishRequest(long expectedConfigVersion, String targetType, String targetNamespace) {
    }

    public record WaiveRequest(String reason) {
    }
}
