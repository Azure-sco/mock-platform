package com.xuntian.mock.control.audit;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.common.PageResult;
import com.xuntian.mock.control.identity.OperatorGuard;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin/v1/audits")
public final class AuditAdminController {

    private final AuditQueryService service;
    private final OperatorGuard operatorGuard;

    public AuditAdminController(AuditQueryService service, OperatorGuard operatorGuard) {
        this.service = service;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping
    public ApiResponse<PageResult<AuditRecord>> find(
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        AuditFilter filter = new AuditFilter(
                AuditQueryService.optional(requestId, "requestId", 64),
                AuditQueryService.optional(operator, "operator", 128),
                AuditQueryService.optional(action, "action", 64),
                AuditQueryService.optional(objectType, "objectType", 64),
                AuditQueryService.optional(objectId, "objectId", 128),
                createdFrom, createdTo);
        return ApiResponse.success(
                service.find(filter, page, size), PlatformController.requestId(request));
    }
}
