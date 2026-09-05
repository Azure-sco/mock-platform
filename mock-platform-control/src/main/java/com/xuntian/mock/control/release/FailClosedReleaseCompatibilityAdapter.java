package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedReleaseCompatibilityAdapter implements ReleaseCompatibilityPort {

    @Override
    public void requireCompatible(String environment, String app, String releaseId) {
        throw new PlatformException(
                ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                "Production Flow compatibility adapter is not configured");
    }
}
