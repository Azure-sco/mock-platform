package com.xuntian.mock.control.web;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public final class DashboardController {

    private final OperatorGuard operatorGuard;
    private final DashboardMapper dashboardMapper;

    public DashboardController(OperatorGuard operatorGuard, DashboardMapper dashboardMapper) {
        this.operatorGuard = operatorGuard;
        this.dashboardMapper = dashboardMapper;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary(HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        DashboardMapper.DashboardMetrics metrics = dashboardMapper.selectMetrics();
        return ApiResponse.success(new DashboardSummary(
                operator.operatorId(), metrics.providers(), metrics.apis(), metrics.scenarios(),
                metrics.releases(), metrics.requests(), metrics.hitRate(), metrics.noMatchRequests(),
                metrics.p95DurationMs(), metrics.callbackSuccessRate(), metrics.callbackRetries()),
                PlatformController.requestId(request));
    }

    public record DashboardSummary(
            String operator,
            long providers,
            long apis,
            long scenarios,
            long releases,
            long requests,
            double hitRate,
            long noMatchRequests,
            long p95DurationMs,
            double callbackSuccessRate,
            long callbackRetries) { }
}
