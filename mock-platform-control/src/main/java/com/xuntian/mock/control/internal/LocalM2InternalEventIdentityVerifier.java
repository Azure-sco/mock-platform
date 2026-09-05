package com.xuntian.mock.control.internal;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"local", "test"})
public final class LocalM2InternalEventIdentityVerifier implements M2InternalEventIdentityVerifier {

    private static final Duration ALLOWED_SKEW = Duration.ofMinutes(5);
    private final byte[] signingKey;
    private final Clock clock;
    private final Map<String, Instant> nonces = new ConcurrentHashMap<>();

    public LocalM2InternalEventIdentityVerifier(Environment environment, Clock clock) {
        String key = environment.getProperty("mock.control.internal.local-signing-key");
        signingKey = key == null ? null : key.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public void verify(HttpServletRequest request, String bodyChecksum) {
        if (signingKey == null || signingKey.length < 16) {
            throw unauthorized("Local internal signing key is not configured");
        }
        String serviceId = header(request, "X-Service-Id", 128);
        String timestamp = header(request, "X-Service-Timestamp", 32);
        String nonce = header(request, "X-Service-Nonce", 64);
        String signature = header(request, "X-Service-Signature", 128);
        Instant requestTime;
        try {
            requestTime = Instant.ofEpochMilli(Long.parseLong(timestamp));
        } catch (RuntimeException failure) {
            throw unauthorized("Internal service timestamp is invalid");
        }
        Instant now = clock.instant();
        if (Duration.between(requestTime, now).abs().compareTo(ALLOWED_SKEW) > 0) {
            throw unauthorized("Internal service timestamp is outside the allowed window");
        }
        nonces.entrySet().removeIf(entry -> entry.getValue().isBefore(now.minus(ALLOWED_SKEW)));
        if (nonces.putIfAbsent(serviceId + ":" + nonce, now) != null) {
            throw unauthorized("Internal service nonce was already used");
        }
        String signed = timestamp + "\n" + nonce + "\n" + serviceId + "\n" + bodyChecksum;
        byte[] expected = hmac(signed);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException failure) {
            throw unauthorized("Internal service signature is invalid");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            nonces.remove(serviceId + ":" + nonce);
            throw unauthorized("Internal service signature is invalid");
        }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Internal signature verification failed", failure);
        }
    }

    private String header(HttpServletRequest request, String name, int maxLength) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw unauthorized(name + " is required");
        }
        return value.trim();
    }

    private PlatformException unauthorized(String message) {
        return new PlatformException(ErrorCode.UNAUTHORIZED, message);
    }
}
