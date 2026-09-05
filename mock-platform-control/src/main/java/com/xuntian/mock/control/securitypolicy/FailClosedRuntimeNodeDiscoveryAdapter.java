package com.xuntian.mock.control.securitypolicy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Profile("!local & !test")
public final class FailClosedRuntimeNodeDiscoveryAdapter implements RuntimeNodeDiscoveryPort {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Set<String> readyNodes(String appCode, String environment) {
        return Set.of();
    }

    @Override
    public boolean removeFromTraffic(String runtimeNodeId) {
        return false;
    }
}
