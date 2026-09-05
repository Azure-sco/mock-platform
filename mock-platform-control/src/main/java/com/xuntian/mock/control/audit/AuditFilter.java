package com.xuntian.mock.control.audit;

import java.time.Instant;

public record AuditFilter(
        String requestId,
        String operator,
        String action,
        String objectType,
        String objectId,
        Instant createdFrom,
        Instant createdTo) { }
