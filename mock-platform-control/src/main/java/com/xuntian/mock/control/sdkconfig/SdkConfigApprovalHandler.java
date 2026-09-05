package com.xuntian.mock.control.sdkconfig;

import com.xuntian.mock.control.approval.ApprovalSubjectHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class SdkConfigApprovalHandler implements ApprovalSubjectHandler {

    public static final String OBJECT_TYPE = "SDK_CONFIG_ENVELOPE";
    private final SdkConfigMapper mapper;

    public SdkConfigApprovalHandler(SdkConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String objectType() {
        return OBJECT_TYPE;
    }

    @Override
    public ApprovalSubject currentSubject(long objectId) {
        SdkConfigEnvelopeRecord record = mapper.selectEnvelope(objectId);
        return record == null ? null : new ApprovalSubject(record.checksum(), record.createdBy());
    }

    @Override
    public int markPending(long objectId, long requestId, String checksum) {
        return mapper.markPendingApproval(objectId, requestId, checksum);
    }

    @Override
    public int markApproved(long objectId, long requestId, String checksum, Instant approvedAt) {
        return mapper.markApproved(objectId, requestId, checksum);
    }

    @Override
    public int markRejected(long objectId, long requestId, String checksum) {
        return mapper.markRejected(objectId, requestId, checksum);
    }
}
