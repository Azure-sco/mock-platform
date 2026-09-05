package com.xuntian.mock.control.web;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.identity.OperatorIdentityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/platform")
public final class PlatformController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health(HttpServletRequest request) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("service", "mock-platform-control");
        health.put("status", "UP");
        health.put("phase", "M1");
        return ApiResponse.success(health, requestId(request));
    }

    public static String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(OperatorIdentityFilter.REQUEST_ID_ATTRIBUTE);
    }
}
