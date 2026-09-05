package com.xuntian.mock.control.approval;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/approvals")
public final class ApprovalAdminController {

    private final ApprovalService approvalService;
    private final OperatorGuard operatorGuard;

    public ApprovalAdminController(ApprovalService approvalService, OperatorGuard operatorGuard) {
        this.approvalService = approvalService;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping
    public ApiResponse<List<ApprovalService.ApprovalView>> findAll(HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(approvalService.findAll(), PlatformController.requestId(request));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ApprovalService.ApprovalView> approve(
            @PathVariable long id,
            @RequestBody(required = false) DecisionRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN", "MOCK_APPROVER");
        String comment = body == null ? null : body.comment();
        return ApiResponse.success(
                approvalService.decide(id, "APPROVE", comment, operator),
                PlatformController.requestId(request));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ApprovalService.ApprovalView> reject(
            @PathVariable long id,
            @RequestBody DecisionRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN", "MOCK_APPROVER");
        return ApiResponse.success(
                approvalService.decide(id, "REJECT", body == null ? null : body.comment(), operator),
                PlatformController.requestId(request));
    }

    public record DecisionRequest(String comment) {
    }
}
