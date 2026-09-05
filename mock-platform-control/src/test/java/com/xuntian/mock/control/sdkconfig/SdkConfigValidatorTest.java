package com.xuntian.mock.control.sdkconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkConfigValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SdkConfigValidator validator = new SdkConfigValidator();

    @Test
    void acceptsFinalRoutingShapeWithExactScopedHeaderPolicy() throws Exception {
        JsonNode payload = json("""
                {"allowedBusinessHeaders":["X-Business-Id"],
                 "additionalSensitiveHeaders":["X-Provider-Signature"]}
                """);
        SdkConfigValidator.PolicyBundle policy = new SdkConfigValidator.PolicyBundle(
                11L, "SDK_HEADER_FILTER", "provider:payments",
                Checksum.sha256Hex(CanonicalJson.write(payload)), payload);

        assertThatCode(() -> validator.validate(
                "order-app", "DEV", routing(), List.of(policy),
                Instant.parse("2026-08-31T00:00:00Z"), null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsScopeOrPayloadChecksumDrift() throws Exception {
        JsonNode payload = json("""
                {"allowedBusinessHeaders":["X-Business-Id"],
                 "additionalSensitiveHeaders":["X-Provider-Signature"]}
                """);
        SdkConfigValidator.PolicyBundle wrongScope = new SdkConfigValidator.PolicyBundle(
                11L, "SDK_HEADER_FILTER", "default",
                Checksum.sha256Hex(CanonicalJson.write(payload)), payload);
        assertThatThrownBy(() -> validator.validate(
                "order-app", "DEV", routing(), List.of(wrongScope),
                Instant.parse("2026-08-31T00:00:00Z"), null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("scopeKey");

        SdkConfigValidator.PolicyBundle wrongChecksum = new SdkConfigValidator.PolicyBundle(
                11L, "SDK_HEADER_FILTER", "provider:payments", "0".repeat(64), payload);
        assertThatThrownBy(() -> validator.validate(
                "order-app", "DEV", routing(), List.of(wrongChecksum),
                Instant.parse("2026-08-31T00:00:00Z"), null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("canonical payload");
    }

    @Test
    void rejectsHttpRuntimeInProductionAndSensitiveAllowedHeader() throws Exception {
        JsonNode realRouting = json("""
                {"runtimeBaseUri":"http://runtime.internal","allowRequestOverride":false,
                 "defaultRoute":{"mode":"REAL","unavailablePolicy":"FAST_FAIL",
                   "allowedBusinessHeaders":[],"additionalSensitiveHeaders":[],"allowedRealHosts":[]},
                 "providerRoutes":{},"apiRoutes":{}}
                """);
        assertThatThrownBy(() -> validator.validate(
                "order-app", "PROD", realRouting, List.of(), Instant.now(), null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("runtimeBaseUri");

        JsonNode payload = json("""
                {"allowedBusinessHeaders":["Authorization"],"additionalSensitiveHeaders":[]}
                """);
        SdkConfigValidator.PolicyBundle policy = new SdkConfigValidator.PolicyBundle(
                11L, "SDK_HEADER_FILTER", "provider:payments",
                Checksum.sha256Hex(CanonicalJson.write(payload)), payload);
        JsonNode routing = routing();
        ((com.fasterxml.jackson.databind.node.ObjectNode) routing.path("providerRoutes").path("payments"))
                .putArray("allowedBusinessHeaders").add("Authorization");
        assertThatThrownBy(() -> validator.validate(
                "order-app", "DEV", routing, List.of(policy), Instant.now(), null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("sensitive");
    }

    private JsonNode routing() throws Exception {
        return json("""
                {"runtimeBaseUri":"https://runtime.internal","allowRequestOverride":false,
                 "defaultRoute":{"mode":"REAL","unavailablePolicy":"FAST_FAIL",
                   "allowedBusinessHeaders":[],"additionalSensitiveHeaders":[],"allowedRealHosts":[]},
                 "providerRoutes":{"payments":{"mode":"MOCK","unavailablePolicy":"FAST_FAIL",
                   "allowedBusinessHeaders":["X-Business-Id"],
                   "additionalSensitiveHeaders":["X-Provider-Signature"],"allowedRealHosts":[],
                   "headerFilterPolicyVersionId":11}},
                 "apiRoutes":{}}
                """);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
