package com.xuntian.mock.control.securitypolicy;

import java.util.Set;

public interface RuntimeNodeDiscoveryPort {

    boolean available();

    Set<String> readyNodes(String appCode, String environment);

    boolean removeFromTraffic(String runtimeNodeId);
}
