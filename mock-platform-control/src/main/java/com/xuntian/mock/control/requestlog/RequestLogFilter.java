package com.xuntian.mock.control.requestlog;

import java.time.Instant;

public record RequestLogFilter(
        String traceId,
        String providerCode,
        String apiCode,
        String scenarioId,
        String appCode,
        String mockRequestId,
        String businessNoHmac,
        String hmacKeyVersion,
        Instant createdFrom,
        Instant createdTo) {
}
