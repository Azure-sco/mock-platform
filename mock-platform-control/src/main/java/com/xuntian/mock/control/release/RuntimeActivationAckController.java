package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/v1/runtime-activation-acks")
public final class RuntimeActivationAckController {

    private final RuntimeAckIdentityVerifier identityVerifier;
    private final RuntimeActivationAckService service;

    public RuntimeActivationAckController(
            RuntimeAckIdentityVerifier identityVerifier,
            RuntimeActivationAckService service) {
        this.identityVerifier = identityVerifier;
        this.service = service;
    }

    @PostMapping
    public ApiResponse<ReleaseTransactionService.AckResult> acknowledge(
            @RequestBody RuntimeActivationAckService.AckCommand body,
            HttpServletRequest request) {
        identityVerifier.verify(request);
        return ApiResponse.success(service.acknowledge(body), PlatformController.requestId(request));
    }
}
