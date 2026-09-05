package com.xuntian.mock.control.flow;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PageResult;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/admin/v1/flow-instances")
public final class FlowInstanceAdminController {

    private final FlowInstanceService service;
    private final OperatorGuard guard;

    public FlowInstanceAdminController(FlowInstanceService service, OperatorGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    public ApiResponse<PageResult<FlowInstanceService.FlowInstanceView>> find(
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String appCode,
            @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) String flowCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        guard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        FlowInstanceFilter filter = new FlowInstanceFilter(
                normalizeEnvironment(environment),
                FlowInstanceService.optional(appCode, "appCode", 128),
                FlowInstanceService.optional(providerCode, "providerCode", 64),
                FlowInstanceService.optional(flowCode, "flowCode", 64),
                status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT));
        return ApiResponse.success(service.find(filter, page, size), PlatformController.requestId(request));
    }

    @GetMapping("/{flowKey}")
    public ApiResponse<FlowInstanceService.FlowInstanceView> detail(
            @PathVariable String flowKey,
            HttpServletRequest request) {
        guard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.detail(flowKey), PlatformController.requestId(request));
    }

    @GetMapping("/{flowKey}/events")
    public ApiResponse<List<FlowEventRecord>> events(
            @PathVariable String flowKey,
            HttpServletRequest request) {
        guard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.events(flowKey), PlatformController.requestId(request));
    }

    @PostMapping("/{flowKey}/transition")
    public ApiResponse<FlowInstanceService.OperationResult> transition(
            @PathVariable String flowKey,
            @RequestBody TransitionRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Second-Confirmation", required = false) String confirmation,
            HttpServletRequest request) {
        OperatorContext operator = guard.requireAnyRole("MOCK_ADMIN");
        confirm(body.requestId(), body.confirmed(), idempotencyKey, confirmation);
        return ApiResponse.success(
                service.transition(flowKey, body.transitionId(), body.requestId(), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/{flowKey}/reset")
    public ApiResponse<FlowInstanceService.OperationResult> reset(
            @PathVariable String flowKey,
            @RequestBody ResetRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Second-Confirmation", required = false) String confirmation,
            HttpServletRequest request) {
        OperatorContext operator = guard.requireAnyRole("MOCK_ADMIN");
        confirm(body.requestId(), body.confirmed(), idempotencyKey, confirmation);
        return ApiResponse.success(
                service.reset(flowKey, body.requestId(), body.keepPinnedVersion(), operator),
                PlatformController.requestId(request));
    }

    @DeleteMapping("/{flowKey}")
    public ApiResponse<FlowInstanceService.OperationResult> delete(
            @PathVariable String flowKey,
            @RequestBody OperationRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Second-Confirmation", required = false) String confirmation,
            HttpServletRequest request) {
        OperatorContext operator = guard.requireAnyRole("MOCK_ADMIN");
        confirm(body.requestId(), body.confirmed(), idempotencyKey, confirmation);
        return ApiResponse.success(
                service.delete(flowKey, body.requestId(), operator),
                PlatformController.requestId(request));
    }

    private static void confirm(
            String requestId,
            boolean confirmed,
            String idempotencyKey,
            String confirmation) {
        if (!confirmed || !"true".equalsIgnoreCase(confirmation)
                || requestId == null || !requestId.equals(idempotencyKey)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST,
                    "Matching requestId/Idempotency-Key and second confirmation are required");
        }
    }

    private static String normalizeEnvironment(String value) {
        String normalized = FlowInstanceService.optional(value, "environment", 32);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    public record TransitionRequest(String transitionId, String requestId, boolean confirmed) { }
    public record ResetRequest(String requestId, boolean keepPinnedVersion, boolean confirmed) { }
    public record OperationRequest(String requestId, boolean confirmed) { }
}
