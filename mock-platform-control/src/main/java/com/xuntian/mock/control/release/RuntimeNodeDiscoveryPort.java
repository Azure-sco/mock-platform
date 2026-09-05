package com.xuntian.mock.control.release;

import java.util.List;
import java.time.Duration;

public interface RuntimeNodeDiscoveryPort {

    List<RuntimeNode> registeredReadyNodes(String environment, String app);

    boolean continuouslyDeregistered(String environment, String app, String nodeId, Duration duration);

    record RuntimeNode(String nodeId) {
    }
}
