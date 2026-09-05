package com.xuntian.mock.control.release;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
public final class LocalReleaseSecurityPolicyGate implements ReleaseSecurityPolicyGate {

    @Override
    public void requirePublishedAndBound(String environment, String app) {
        // Local/test fixture explicitly models no Release-class security policies.
    }
}
