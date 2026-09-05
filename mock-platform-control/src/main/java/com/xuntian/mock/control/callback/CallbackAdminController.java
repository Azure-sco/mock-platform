package com.xuntian.mock.control.callback;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.common.PageResult;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;

@RestController
@RequestMapping("/api/admin/v1/callback-tasks")
public final class CallbackAdminController {

    private final CallbackService service;
    private final OperatorGuard guard;

    public CallbackAdminController(CallbackService service, OperatorGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    public ApiResponse<PageResult<CallbackTaskRecord>> find(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) String apiCode,
            @RequestParam(required = false) Long flowInstanceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        guard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        CallbackTaskFilter filter = new CallbackTaskFilter(
                status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT),
                CallbackService.optional(providerCode, "providerCode", 64),
                CallbackService.optional(apiCode, "apiCode", 64),
                flowInstanceId, createdFrom, createdTo);
        return ApiResponse.success(service.find(filter, page, size), PlatformController.requestId(request));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<CallbackService.CallbackDetail> detail(
            @PathVariable String taskId,
            HttpServletRequest request) {
        guard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.detail(taskId), PlatformController.requestId(request));
    }

    @GetMapping("/{taskId}/attempts")
    public ApiResponse<java.util.List<CallbackAttemptRecord>> attempts(
            @PathVariable String taskId,
            HttpServletRequest request) {
        guard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(service.detail(taskId).attempts(), PlatformController.requestId(request));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<CallbackService.OperationResult> retry(
            @PathVariable String taskId,
            @RequestBody RetryRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Second-Confirmation", required = false) String confirmation,
            HttpServletRequest request) {
        OperatorContext operator = guard.requireAnyRole("MOCK_ADMIN");
        requireConfirmed(body.requestId(), body.confirmed(), idempotencyKey, confirmation);
        return ApiResponse.success(service.retry(taskId, body.requestId(), body.delayMs(), operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<CallbackService.OperationResult> cancel(
            @PathVariable String taskId,
            @RequestBody OperationRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Second-Confirmation", required = false) String confirmation,
            HttpServletRequest request) {
        OperatorContext operator = guard.requireAnyRole("MOCK_ADMIN");
        requireConfirmed(body.requestId(), body.confirmed(), idempotencyKey, confirmation);
        return ApiResponse.success(service.cancel(taskId, body.requestId(), operator), PlatformController.requestId(request));
    }

    private void requireConfirmed(
            String requestId,
            boolean confirmed,
            String idempotencyKey,
            String confirmation) {
        if (!confirmed || !"true".equalsIgnoreCase(confirmation)
                || requestId == null || !requestId.equals(idempotencyKey)) {
            throw new com.xuntian.mock.common.PlatformException(
                    com.xuntian.mock.common.ErrorCode.INVALID_REQUEST,
                    "Matching requestId/Idempotency-Key and second confirmation are required");
        }
    }

    public record RetryRequest(String requestId, long delayMs, boolean confirmed) { }
    public record OperationRequest(String requestId, boolean confirmed) { }
}
