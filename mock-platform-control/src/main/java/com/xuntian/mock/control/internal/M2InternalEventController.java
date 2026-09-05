package com.xuntian.mock.control.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.control.sdkconfig.SdkConfigService;
import com.xuntian.mock.control.sdkconfig.SdkConfigTransactionService;
import com.xuntian.mock.control.securitypolicy.RuntimePolicyAckService;
import com.xuntian.mock.control.web.PlatformController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/v1")
public final class M2InternalEventController {

    private final M2InternalEventIdentityVerifier identityVerifier;
    private final SdkConfigService sdkConfigService;
    private final RuntimePolicyAckService runtimePolicyAckService;

    public M2InternalEventController(
            M2InternalEventIdentityVerifier identityVerifier,
            SdkConfigService sdkConfigService,
            RuntimePolicyAckService runtimePolicyAckService) {
        this.identityVerifier = identityVerifier;
        this.sdkConfigService = sdkConfigService;
        this.runtimePolicyAckService = runtimePolicyAckService;
    }

    @PostMapping("/sdk-config-events")
    public ApiResponse<SdkConfigTransactionService.AckResult> sdkConfigEvent(
            @RequestBody SdkConfigEventRequest body,
            HttpServletRequest request) {
        identityVerifier.verify(request, checksum(body.canonical()));
        SdkConfigTransactionService.AckResult result = sdkConfigService.recordEvent(
                new SdkConfigService.EventCommand(
                        body.activationId(), body.appCode(), body.environment(), body.sdkInstanceId(),
                        body.envelopeId(), body.oldConfigVersion(), body.newConfigVersion(),
                        body.securityPolicyRefs(), body.status(), body.effectiveAt(),
                        body.errorMasked(), body.sourceAuditRef()));
        return ApiResponse.success(result, PlatformController.requestId(request));
    }

    @PostMapping("/runtime-policy-acks")
    public ApiResponse<RuntimePolicyAckService.AckView> runtimePolicyAck(
            @RequestBody RuntimePolicyAckRequest body,
            HttpServletRequest request) {
        identityVerifier.verify(request, checksum(body.canonical()));
        RuntimePolicyAckService.AckView result = runtimePolicyAckService.record(
                new RuntimePolicyAckService.Command(
                        body.runtimeNodeId(), body.bindingId(), body.scopeKey(), body.environment(), body.appCode(),
                        body.policyVersionId(), body.bindingVersion(), body.status(),
                        body.errorMasked(), body.reportedAt()));
        return ApiResponse.success(result, PlatformController.requestId(request));
    }

    private String checksum(Map<String, Object> body) {
        return Checksum.sha256Hex(CanonicalJson.write(body));
    }

    public record SdkConfigEventRequest(
            String activationId,
            String appCode,
            String environment,
            String sdkInstanceId,
            long envelopeId,
            Long oldConfigVersion,
            long newConfigVersion,
            JsonNode securityPolicyRefs,
            String status,
            Instant effectiveAt,
            String errorMasked,
            String sourceAuditRef) {

        Map<String, Object> canonical() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("activationId", activationId);
            value.put("appCode", appCode);
            value.put("environment", environment);
            value.put("sdkInstanceId", sdkInstanceId);
            value.put("envelopeId", envelopeId);
            value.put("oldConfigVersion", oldConfigVersion);
            value.put("newConfigVersion", newConfigVersion);
            value.put("securityPolicyRefs", securityPolicyRefs);
            value.put("status", status);
            value.put("effectiveAt", effectiveAt == null ? null : effectiveAt.toString());
            value.put("errorMasked", errorMasked);
            value.put("sourceAuditRef", sourceAuditRef);
            return value;
        }
    }

    public record RuntimePolicyAckRequest(
            String runtimeNodeId,
            String bindingId,
            String scopeKey,
            String environment,
            String appCode,
            long policyVersionId,
            long bindingVersion,
            String status,
            String errorMasked,
            Instant reportedAt) {

        Map<String, Object> canonical() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("runtimeNodeId", runtimeNodeId);
            value.put("bindingId", bindingId);
            value.put("scopeKey", scopeKey);
            value.put("environment", environment);
            value.put("appCode", appCode);
            value.put("policyVersionId", policyVersionId);
            value.put("bindingVersion", bindingVersion);
            value.put("status", status);
            value.put("errorMasked", errorMasked);
            value.put("reportedAt", reportedAt == null ? null : reportedAt.toString());
            return value;
        }
    }
}
