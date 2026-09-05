package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
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
public final class ReleaseAdminController {

    private final ReleaseService releaseService;
    private final OperatorGuard operatorGuard;

    public ReleaseAdminController(ReleaseService releaseService, OperatorGuard operatorGuard) {
        this.releaseService = releaseService;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping("/releases")
    public ApiResponse<List<ReleaseService.ReleaseSummary>> findAll(HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(releaseService.findAll(), PlatformController.requestId(request));
    }

    @GetMapping("/releases/{id}")
    public ApiResponse<ReleaseService.ReleaseDetail> find(
            @PathVariable String id,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(releaseService.find(id), PlatformController.requestId(request));
    }

    @GetMapping("/active-releases")
    public ApiResponse<ActiveReleaseRecord> active(
            @RequestParam String environment,
            @RequestParam String app,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(releaseService.active(environment, app), PlatformController.requestId(request));
    }

    @GetMapping("/release-activations/{id}")
    public ApiResponse<ReleaseService.ActivationView> activation(
            @PathVariable String id,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(releaseService.activation(id), PlatformController.requestId(request));
    }

    @PostMapping("/releases/validate")
    public ApiResponse<ReleaseService.ValidationView> validate(
            @RequestBody ReleaseRequest body,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(releaseService.validate(body.command()), PlatformController.requestId(request));
    }

    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReleaseService.ReleaseDetail> create(
            @RequestBody ReleaseRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        return ApiResponse.success(releaseService.create(body.command(), operator), PlatformController.requestId(request));
    }

    @PostMapping("/releases/{id}/publish")
    public ApiResponse<ReleaseService.ActivationView> publish(
            @PathVariable String id,
            @RequestBody ActivationRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN", "MOCK_RELEASE_OPERATOR");
        return ApiResponse.success(
                releaseService.publish(id, body.expectedActivationVersion(), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/releases/{id}/rollback")
    public ApiResponse<ReleaseService.ActivationView> rollback(
            @PathVariable String id,
            @RequestBody ActivationRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN", "MOCK_RELEASE_OPERATOR");
        return ApiResponse.success(
                releaseService.rollback(id, body.expectedActivationVersion(), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/release-activations/{activationId}/targets/{nodeId}/waive")
    public ApiResponse<ReleaseService.ActivationView> waive(
            @PathVariable String activationId,
            @PathVariable String nodeId,
            @RequestBody WaiveRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_RELEASE_OVERRIDE");
        return ApiResponse.success(
                releaseService.waive(activationId, nodeId, body.reason(), body.confirmed(), operator),
                PlatformController.requestId(request));
    }

    @GetMapping("/releases/{id}/diff")
    public ApiResponse<ReleaseService.DiffView> diff(
            @PathVariable String id,
            @RequestParam String compareTo,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(releaseService.diff(id, compareTo), PlatformController.requestId(request));
    }

    public record ReleaseRequest(
            String releaseCode,
            String environment,
            String appCode,
            List<Long> scenarioVersionIds,
            String releaseNote) {

        ReleaseService.CreateCommand command() {
            return new ReleaseService.CreateCommand(
                    releaseCode, environment, appCode, scenarioVersionIds, releaseNote);
        }
    }

    public record ActivationRequest(long expectedActivationVersion) {
    }

    public record WaiveRequest(String reason, boolean confirmed) {
    }
}
