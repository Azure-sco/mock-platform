package com.xuntian.mock.control.securitypolicy;

import com.xuntian.mock.control.approval.ApprovalSubjectHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class SecurityPolicyApprovalHandler implements ApprovalSubjectHandler {

    public static final String OBJECT_TYPE = "SECURITY_POLICY_VERSION";

    private final SecurityPolicyMapper mapper;

    public SecurityPolicyApprovalHandler(SecurityPolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String objectType() {
        return OBJECT_TYPE;
    }

    @Override
    public ApprovalSubject currentSubject(long objectId) {
        SecurityPolicyVersionRecord record = mapper.selectVersionById(objectId);
        return record == null ? null : new ApprovalSubject(record.checksum(), record.createdBy());
    }

    @Override
    public int markPending(long objectId, long requestId, String checksum) {
        return mapper.attachApproval(objectId, checksum, requestId);
    }

    @Override
    public int markApproved(long objectId, long requestId, String checksum, Instant approvedAt) {
        return mapper.markApproved(objectId, checksum, requestId);
    }

    @Override
    public int markRejected(long objectId, long requestId, String checksum) {
        return mapper.markApprovalRejected(objectId, requestId);
    }
}
