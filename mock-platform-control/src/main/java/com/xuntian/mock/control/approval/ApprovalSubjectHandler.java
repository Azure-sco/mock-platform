package com.xuntian.mock.control.approval;

import java.time.Instant;

/**
 * State transition hook for an immutable object that is governed by checksum approval.
 * Implementations must use affected-row checks so a concurrent content/status change fails closed.
 */
public interface ApprovalSubjectHandler {

    String objectType();

    ApprovalSubject currentSubject(long objectId);

    int markPending(long objectId, long requestId, String checksum);

    int markApproved(long objectId, long requestId, String checksum, Instant approvedAt);

    int markRejected(long objectId, long requestId, String checksum);

    record ApprovalSubject(String checksum, String lastModifiedBy) {
    }
}
