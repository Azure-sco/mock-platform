package com.xuntian.mock.control.securitypolicy;

import java.time.Instant;

public interface AdmissionLeasePublisher {

    boolean publishIfNewer(
            String environment,
            String appCode,
            long bindingVersion,
            Instant issuedAt,
            Instant notAfter,
            byte[] canonicalEnvelope);
}
