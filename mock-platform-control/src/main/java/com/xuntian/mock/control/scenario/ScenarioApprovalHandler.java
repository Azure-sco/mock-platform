package com.xuntian.mock.control.scenario;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.approval.ApprovalSubjectHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class ScenarioApprovalHandler implements ApprovalSubjectHandler {

    public static final String OBJECT_TYPE = "SCENARIO_VERSION";
    private final ScenarioMapper mapper;

    public ScenarioApprovalHandler(ScenarioMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String objectType() {
        return OBJECT_TYPE;
    }

    @Override
    public ApprovalSubject currentSubject(long objectId) {
        ScenarioVersionRecord version = mapper.selectVersionById(objectId);
        if (version == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Scenario version not found");
        }
        return new ApprovalSubject(version.checksum(), version.createdBy());
    }

    @Override
    public int markPending(long objectId, long requestId, String checksum) {
        return mapper.markPendingApproval(objectId, requestId, checksum);
    }

    @Override
    public int markApproved(long objectId, long requestId, String checksum, Instant approvedAt) {
        return mapper.markApproved(objectId, requestId, checksum, approvedAt);
    }

    @Override
    public int markRejected(long objectId, long requestId, String checksum) {
        return mapper.markRejected(objectId, requestId, checksum);
    }
}
