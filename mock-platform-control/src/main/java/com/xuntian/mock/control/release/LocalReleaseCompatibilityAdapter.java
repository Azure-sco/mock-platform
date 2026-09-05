package com.xuntian.mock.control.release;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
public final class LocalReleaseCompatibilityAdapter implements ReleaseCompatibilityPort {

    @Override
    public void requireCompatible(String environment, String app, String releaseId) {
        // M2 local/test fixtures have no Flow instances. Production requires the M3 compatibility adapter.
    }
}
