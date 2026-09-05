package com.xuntian.mock.control.sdkconfig;

import java.time.Duration;
import java.util.Set;

public interface SdkInstanceDiscoveryPort {

    boolean available();

    Set<String> registeredReadyInstances(String appCode, String environment);

    boolean deregisteredFor(String appCode, String environment, String instanceId, Duration duration);

    boolean removeFromTraffic(String appCode, String environment, String instanceId);

    boolean removedFromTraffic(String appCode, String environment, String instanceId);
}
