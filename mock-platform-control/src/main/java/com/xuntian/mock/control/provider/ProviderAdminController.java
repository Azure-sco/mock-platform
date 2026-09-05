package com.xuntian.mock.control.provider;

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
@RequestMapping("/api/admin/v1/providers")
public final class ProviderAdminController {

    private final ProviderService providerService;
    private final OperatorGuard operatorGuard;

    public ProviderAdminController(ProviderService providerService, OperatorGuard operatorGuard) {
        this.providerService = providerService;
        this.operatorGuard = operatorGuard;
    }

    @GetMapping
    public ApiResponse<List<ProviderRecord>> findAll(HttpServletRequest request) {
        operatorGuard.requireAnyRole("MOCK_VIEWER", "MOCK_ADMIN");
        return ApiResponse.success(providerService.findAll(), PlatformController.requestId(request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProviderRecord> create(
            @RequestBody CreateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        ProviderRecord created = providerService.create(
                new ProviderService.CreateCommand(
                        body.providerCode(), body.providerName(), body.owner(), body.status()),
                operator);
        return ApiResponse.success(created, PlatformController.requestId(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProviderRecord> update(
            @PathVariable long id,
            @RequestBody UpdateRequest body,
            HttpServletRequest request) {
        OperatorContext operator = operatorGuard.requireAnyRole("MOCK_ADMIN");
        ProviderRecord updated = providerService.update(
                id,
                new ProviderService.UpdateCommand(body.providerName(), body.owner(), body.status()),
                operator);
        return ApiResponse.success(updated, PlatformController.requestId(request));
    }

    public record CreateRequest(String providerCode, String providerName, String owner, String status) {
    }

    public record UpdateRequest(String providerName, String owner, String status) {
    }
}
