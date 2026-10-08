package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Profile("local & local-published & !test")
public final class LocalPublishedHeartbeat {
    private final StringRedisTemplate redis;
    private final RuntimeProperties properties;

    public LocalPublishedHeartbeat(StringRedisTemplate redis, RuntimeProperties properties,
            @Value("${server.address:}") String address) {
        if (!"127.0.0.1".equals(address) || !"TEST".equals(properties.getEnvironment())
                || properties.getPublishedApps().isEmpty() || properties.getSnapshotPublicKeys().isEmpty()
                || !properties.getLocalAppTokens().values().containsAll(properties.getPublishedApps())) {
            throw new IllegalStateException("Local publication requires loopback, TEST, trusted keys and admitted apps");
        }
        this.redis = redis;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 3000)
    public void announce() {
        for (String app : properties.getPublishedApps()) {
            redis.opsForValue().set("mock:local-runtime:" + properties.getEnvironment() + ":" + app + ":"
                    + properties.getRuntimeNodeId(), "READY", Duration.ofSeconds(10));
        }
    }
}
