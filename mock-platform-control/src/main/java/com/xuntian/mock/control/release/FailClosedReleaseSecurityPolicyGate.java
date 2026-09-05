package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedReleaseSecurityPolicyGate implements ReleaseSecurityPolicyGate {

    @Override
    public void requirePublishedAndBound(String environment, String app) {
        throw new PlatformException(
                ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                "Production Release security-policy binding adapter is not configured");
    }
}
