package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@Profile({"local", "test"})
public final class LocalRuntimeAckIdentityVerifier implements RuntimeAckIdentityVerifier {

    private final Clock clock;

    public LocalRuntimeAckIdentityVerifier(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String verify(HttpServletRequest request) {
        String service = request.getHeader("X-Mock-Runtime-Service");
        String timestamp = request.getHeader("X-Mock-Runtime-Timestamp");
        String nonce = request.getHeader("X-Mock-Runtime-Nonce");
        String signature = request.getHeader("X-Mock-Runtime-Signature");
        if (service == null || service.isBlank() || nonce == null || nonce.isBlank()
                || !"local-test-signature".equals(signature)) {
            throw new PlatformException(ErrorCode.UNAUTHORIZED, "Runtime service identity is invalid");
        }
        try {
            Instant issuedAt = Instant.ofEpochMilli(Long.parseLong(timestamp));
            if (Math.abs(clock.instant().toEpochMilli() - issuedAt.toEpochMilli()) > 60_000L) {
                throw new PlatformException(ErrorCode.UNAUTHORIZED, "Runtime service timestamp is stale");
            }
        } catch (RuntimeException invalid) {
            if (invalid instanceof PlatformException platform) throw platform;
            throw new PlatformException(ErrorCode.UNAUTHORIZED, "Runtime service timestamp is invalid", invalid);
        }
        return service;
    }
}
