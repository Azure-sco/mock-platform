package com.xuntian.mock.control.sdkconfig;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Component
@Profile("!local & !test")
public final class FailClosedSdkInstanceDiscoveryAdapter implements SdkInstanceDiscoveryPort {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Set<String> registeredReadyInstances(String appCode, String environment) {
        return Set.of();
    }

    @Override
    public boolean deregisteredFor(String appCode, String environment, String instanceId, Duration duration) {
        return false;
    }

    @Override
    public boolean removeFromTraffic(String appCode, String environment, String instanceId) {
        return false;
    }

    @Override
    public boolean removedFromTraffic(String appCode, String environment, String instanceId) {
        return false;
    }
}
