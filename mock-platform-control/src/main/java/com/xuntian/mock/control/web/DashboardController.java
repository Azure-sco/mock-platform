package com.xuntian.mock.control.web;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public final class DashboardController {

    private final OperatorGuard operatorGuard;

    public DashboardController(OperatorGuard operatorGuard) {
        this.operatorGuard = operatorGuard;
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operator", operator.operatorId());
        summary.put("providers", 0);
        summary.put("apis", 0);
        summary.put("scenarios", 0);
        summary.put("releases", 0);
        summary.put("requests", 0);
        return ApiResponse.success(summary, PlatformController.requestId(request));
    }
}
