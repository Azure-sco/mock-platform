package com.xuntian.mock.control.sdkconfig;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Profile({"local", "test"})
public final class LocalSdkInstanceDiscoveryAdapter implements SdkInstanceDiscoveryPort {

    private final Set<String> configuredInstances;
    private final Set<String> removed = ConcurrentHashMap.newKeySet();

    public LocalSdkInstanceDiscoveryAdapter(Environment environment) {
        String configured = environment.getProperty("mock.control.sdk.local-instances", "local-sdk-1");
        configuredInstances = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Set<String> registeredReadyInstances(String appCode, String environment) {
        return configuredInstances.stream()
                .filter(instance -> !removed.contains(instance))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean deregisteredFor(String appCode, String environment, String instanceId, Duration duration) {
        return !configuredInstances.contains(instanceId);
    }

    @Override
    public boolean removeFromTraffic(String appCode, String environment, String instanceId) {
        removed.add(instanceId);
        return true;
    }

    @Override
    public boolean removedFromTraffic(String appCode, String environment, String instanceId) {
        return removed.contains(instanceId) || !configuredInstances.contains(instanceId);
    }
}
