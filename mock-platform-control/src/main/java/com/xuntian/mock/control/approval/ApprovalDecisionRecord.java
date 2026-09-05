package com.xuntian.mock.control.approval;

import java.time.Instant;

public record ApprovalDecisionRecord(
        long id,
        long approvalRequestId,
        String reviewer,
        String decision,
        String comment,
        Instant decidedAt) {
}
