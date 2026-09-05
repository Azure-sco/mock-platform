package com.xuntian.mock.control.requestlog;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.common.PageResult;
import com.xuntian.mock.control.identity.OperatorGuard;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/v1/requests")
public final class RequestLogAdminController {

    private final RequestLogQueryService requestLogService;
    private final OperatorGuard operatorGuard;

    public RequestLogAdminController(RequestLogQueryService requestLogService, OperatorGuard operatorGuard) {
        this.requestLogService = requestLogService;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping
    public ApiResponse<PageResult<RequestLogRecord>> find(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) String apiCode,
            @RequestParam(required = false) String scenarioId,
            @RequestParam(required = false) String appCode,
            @RequestParam(required = false) String mockRequestId,
            @RequestParam(required = false) String businessNoHmac,
            @RequestParam(required = false) String hmacKeyVersion,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        RequestLogFilter filter = new RequestLogFilter(
                optional(traceId, "traceId", 64),
                optional(providerCode, "providerCode", 64),
                optional(apiCode, "apiCode", 64),
                optional(scenarioId, "scenarioId", 64),
                optional(appCode, "appCode", 128),
                optional(mockRequestId, "mockRequestId", 64),
                optional(businessNoHmac, "businessNoHmac", 64),
                optional(hmacKeyVersion, "hmacKeyVersion", 32),
                createdFrom,
                createdTo);
        return ApiResponse.success(
                requestLogService.find(filter, page, size),
                PlatformController.requestId(request));
    }

    @GetMapping("/{requestLogId}")
    public ApiResponse<RequestLogRecord> detail(
            @PathVariable String requestLogId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdDate,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(
                requestLogService.detail(requestLogId, createdDate),
                PlatformController.requestId(request));
    }

    private String optional(String value, String field, int maxLength) {
        return RequestLogQueryService.optional(value, field, maxLength);
    }
}
