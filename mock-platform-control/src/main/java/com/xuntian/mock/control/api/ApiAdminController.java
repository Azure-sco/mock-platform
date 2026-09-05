package com.xuntian.mock.control.api;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.identity.OperatorGuard;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1")
public final class ApiAdminController {

    private final ApiService apiService;
    private final OperatorGuard operatorGuard;

    public ApiAdminController(ApiService apiService, OperatorGuard operatorGuard) {
        this.apiService = apiService;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping("/providers/{providerId}/apis")
    public ApiResponse<List<ApiRecord>> findByProvider(
            @PathVariable long providerId,
            HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(apiService.findByProvider(providerId), PlatformController.requestId(request));
    }

    @PostMapping("/apis")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApiRecord> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        ApiRecord created = apiService.create(
                new ApiService.CreateCommand(
                        body.providerId(),
                        body.apiCode(),
                        body.apiName(),
                        body.httpMethod(),
                        body.path(),
                        body.contentType(),
                        body.owner(),
                        body.status()),
                operator);
        return ApiResponse.success(created, PlatformController.requestId(request));
    }

    @PutMapping("/apis/{id}")
    public ApiResponse<ApiRecord> update(
            @PathVariable long id,
            @RequestBody UpdateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        ApiRecord updated = apiService.update(
                id,
                new ApiService.UpdateCommand(
                        body.apiName(),
                        body.httpMethod(),
                        body.path(),
                        body.contentType(),
                        body.owner(),
                        body.status()),
                operator);
        return ApiResponse.success(updated, PlatformController.requestId(request));
    }

    public record CreateRequest(
            long providerId,
            String apiCode,
            String apiName,
            String httpMethod,
            String path,
            String contentType,
            String owner,
            String status) {
    }

    public record UpdateRequest(
            String apiName,
            String httpMethod,
            String path,
            String contentType,
            String owner,
            String status) {
    }
}
