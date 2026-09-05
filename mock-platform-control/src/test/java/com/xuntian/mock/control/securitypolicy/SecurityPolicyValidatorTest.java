package com.xuntian.mock.control.securitypolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.PlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class SecurityPolicyValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecurityPolicyValidator validator = new SecurityPolicyValidator();

    @Test
    void validatesFinalSdkPolicyPayloadsAndScopes() throws Exception {
        validator.validate(
                SecurityPolicyType.SDK_HEADER_FILTER,
                "api:payments:create",
                json("""
                        {"allowedBusinessHeaders":["X-Business-Id"],
                         "additionalSensitiveHeaders":["X-Provider-Signature"]}
                        """));
        validator.validate(
                SecurityPolicyType.SDK_FALLBACK_REAL,
                "provider:payments",
                json("""
                        {"environment":"DEV","requireReplayableBody":true,
                         "allowedFailureCategories":["CONNECT_TIMEOUT"],
                         "allowedRealHosts":["payments.example.test"]}
                        """));
    }

    @Test
    void rejectsSensitiveHeaderAllowAndLegacyFallbackUrlShape() throws Exception {
        assertThatThrownBy(() -> validator.validate(
                SecurityPolicyType.SDK_HEADER_FILTER,
                "default",
                json("""
                        {"allowedBusinessHeaders":["Authorization"],"additionalSensitiveHeaders":[]}
                        """)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("sensitive");

        assertThatThrownBy(() -> validator.validate(
                SecurityPolicyType.SDK_FALLBACK_REAL,
                "default",
                json("""
                        {"environment":"DEV","requireReplayableBody":true,
                         "allowedFailureCategories":["CONNECT_TIMEOUT"],
                         "allowedBaseUrls":["https://payments.example.test"]}
                        """)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("allowedRealHosts");
    }

    @Test
    void rejectsFallbackRealInProduction() throws Exception {
        assertThatThrownBy(() -> validator.validate(
                SecurityPolicyType.SDK_FALLBACK_REAL,
                "default",
                json("""
                        {"environment":"PROD","requireReplayableBody":true,
                         "allowedFailureCategories":["CONNECT_TIMEOUT"],
                         "allowedRealHosts":["payments.example.com"]}
                        """)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("forbidden in PROD");
    }

    @Test
    void permitsHttpLoopbackOnlyWhenExplicitlyEnabled() throws Exception {
        JsonNode local = json("{\"allowedBaseUrls\":[\"http://127.0.0.1:18080/callback\"]}");
        assertThatThrownBy(() -> validator.validate(SecurityPolicyType.CALLBACK_ALLOWLIST, "local", local))
                .isInstanceOf(PlatformException.class);
        assertThatCode(() -> new SecurityPolicyValidator(true)
                .validate(SecurityPolicyType.CALLBACK_ALLOWLIST, "local", local))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new SecurityPolicyValidator(true).validate(
                SecurityPolicyType.CALLBACK_ALLOWLIST, "local",
                json("{\"allowedBaseUrls\":[\"http://10.0.0.8/callback\"]}")))
                .isInstanceOf(PlatformException.class);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
