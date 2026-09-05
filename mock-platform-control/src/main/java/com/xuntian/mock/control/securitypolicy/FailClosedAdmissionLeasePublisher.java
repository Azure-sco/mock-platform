package com.xuntian.mock.control.securitypolicy;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Profile("!local & !test")
public final class FailClosedAdmissionLeasePublisher implements AdmissionLeasePublisher {

    @Override
    public boolean publishIfNewer(
            String environment,
            String appCode,
            long bindingVersion,
            Instant issuedAt,
            Instant notAfter,
            byte[] canonicalEnvelope) {
        throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Production admission Redis adapter is not configured");
    }
}
