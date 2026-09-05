package com.xuntian.mock.control.release;

public interface RuntimeTrafficGovernancePort {

    void removeFromTraffic(String environment, String app, String nodeId);

    boolean isRemovedFromTraffic(String environment, String app, String nodeId);
}
