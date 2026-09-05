package com.xuntian.mock.control.release;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"local", "test"})
public final class LocalRuntimeTrafficGovernanceAdapter implements RuntimeTrafficGovernancePort {

    private final Set<String> removed = ConcurrentHashMap.newKeySet();

    @Override
    public void removeFromTraffic(String environment, String app, String nodeId) {
        removed.add(key(environment, app, nodeId));
    }

    @Override
    public boolean isRemovedFromTraffic(String environment, String app, String nodeId) {
        return removed.contains(key(environment, app, nodeId));
    }

    private String key(String environment, String app, String nodeId) {
        return environment + "\u0000" + app + "\u0000" + nodeId;
    }
}
