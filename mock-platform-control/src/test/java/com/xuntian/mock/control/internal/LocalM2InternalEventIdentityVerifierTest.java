package com.xuntian.mock.control.internal;

import com.xuntian.mock.common.PlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalM2InternalEventIdentityVerifierTest {

    private static final String KEY = "local-fixture-signing-key";
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void verifiesTimestampedBodyBoundSignatureAndRejectsNonceReplay() throws Exception {
        LocalM2InternalEventIdentityVerifier verifier = verifier(KEY);
        MockHttpServletRequest request = signed("runtime-1", "nonce-1", "body-checksum", KEY);

        assertThatCode(() -> verifier.verify(request, "body-checksum")).doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier.verify(request, "body-checksum"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("nonce");
    }

    @Test
    void failsClosedWithoutAConfiguredServiceKey() {
        LocalM2InternalEventIdentityVerifier verifier = verifier(null);
        assertThatThrownBy(() -> verifier.verify(new MockHttpServletRequest(), "checksum"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not configured");
    }

    private LocalM2InternalEventIdentityVerifier verifier(String key) {
        MockEnvironment environment = new MockEnvironment();
        if (key != null) environment.setProperty("mock.control.internal.local-signing-key", key);
        return new LocalM2InternalEventIdentityVerifier(
                environment, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MockHttpServletRequest signed(
            String serviceId,
            String nonce,
            String bodyChecksum,
            String key) throws Exception {
        String timestamp = Long.toString(NOW.toEpochMilli());
        String content = timestamp + "\n" + nonce + "\n" + serviceId + "\n" + bodyChecksum;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Service-Id", serviceId);
        request.addHeader("X-Service-Timestamp", timestamp);
        request.addHeader("X-Service-Nonce", nonce);
        request.addHeader("X-Service-Signature", HexFormat.of().formatHex(mac.doFinal(
                content.getBytes(StandardCharsets.UTF_8))));
        return request;
    }
}
