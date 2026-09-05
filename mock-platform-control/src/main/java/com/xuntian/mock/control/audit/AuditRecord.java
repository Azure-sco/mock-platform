package com.xuntian.mock.control.audit;

import java.time.Instant;

public record AuditRecord(
        long id,
        String requestId,
        String operator,
        String action,
        String objectType,
        String objectId,
        String objectChecksum,
        String beforeJsonMasked,
        String afterJsonMasked,
        String result,
        String reason,
        Instant createdAt) { }
