package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedRuntimeTrafficGovernanceAdapter implements RuntimeTrafficGovernancePort {

    @Override
    public void removeFromTraffic(String environment, String app, String nodeId) {
        throw missing();
    }

    @Override
    public boolean isRemovedFromTraffic(String environment, String app, String nodeId) {
        throw missing();
    }

    private PlatformException missing() {
        return new PlatformException(
                ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                "Production Runtime traffic-governance adapter is not configured");
    }
}
