package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@Profile("local & local-published & !test")
public final class LocalPublishedNodeDiscovery implements RuntimeNodeDiscoveryPort {
    private final StringRedisTemplate redis;
    private final String nodeId;

    public LocalPublishedNodeDiscovery(StringRedisTemplate redis,
            @Value("${mock.local-published.node-id}") String nodeId,
            @Value("${server.address:}") String address) {
        if (!"127.0.0.1".equals(address) || !nodeId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalStateException("Local publication requires loopback binding and a valid node id");
        }
        this.redis = redis;
        this.nodeId = nodeId;
    }

    @Override
    public List<RuntimeNode> registeredReadyNodes(String environment, String app) {
        if (!"TEST".equals(environment) || !"READY".equals(redis.opsForValue()
                .get("mock:local-runtime:" + environment + ":" + app + ":" + nodeId))) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                    "No local Runtime heartbeat for this TEST application; check Runtime apps and node id");
        }
        return List.of(new RuntimeNode(nodeId));
    }

    @Override
    public boolean continuouslyDeregistered(String environment, String app, String nodeId, Duration duration) {
        // An absent heartbeat is not proof that an instance was removed from traffic.
        return false;
    }
}
