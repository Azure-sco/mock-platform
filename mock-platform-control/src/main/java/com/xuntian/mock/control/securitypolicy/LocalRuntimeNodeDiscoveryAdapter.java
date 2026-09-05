package com.xuntian.mock.control.securitypolicy;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Profile({"local", "test"})
public final class LocalRuntimeNodeDiscoveryAdapter implements RuntimeNodeDiscoveryPort {

    private final Set<String> nodes;
    private final Set<String> removed = ConcurrentHashMap.newKeySet();

    public LocalRuntimeNodeDiscoveryAdapter(Environment environment) {
        nodes = Arrays.stream(environment.getProperty("mock.control.runtime.local-nodes", "local-runtime-1").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Set<String> readyNodes(String appCode, String environment) {
        return nodes.stream().filter(node -> !removed.contains(node)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean removeFromTraffic(String runtimeNodeId) {
        removed.add(runtimeNodeId);
        return true;
    }
}
