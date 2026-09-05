package com.xuntian.mock.control.release;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Duration;

@Component("releaseLocalRuntimeNodeDiscoveryAdapter")
@Profile({"local", "test"})
public final class LocalRuntimeNodeDiscoveryAdapter implements RuntimeNodeDiscoveryPort {

    @Override
    public List<RuntimeNode> registeredReadyNodes(String environment, String app) {
        return List.of();
    }

    @Override
    public boolean continuouslyDeregistered(String environment, String app, String nodeId, Duration duration) {
        return false;
    }
}
