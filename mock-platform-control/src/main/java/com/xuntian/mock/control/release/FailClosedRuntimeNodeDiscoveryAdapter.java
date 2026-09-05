package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Duration;

@Component("releaseFailClosedRuntimeNodeDiscoveryAdapter")
@Profile("!local & !test")
public final class FailClosedRuntimeNodeDiscoveryAdapter implements RuntimeNodeDiscoveryPort {

    @Override
    public List<RuntimeNode> registeredReadyNodes(String environment, String app) {
        throw new PlatformException(
                ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                "Production Runtime service discovery adapter is not configured");
    }

    @Override
    public boolean continuouslyDeregistered(String environment, String app, String nodeId, Duration duration) {
        throw new PlatformException(
                ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                "Production Runtime service discovery adapter is not configured");
    }
}
