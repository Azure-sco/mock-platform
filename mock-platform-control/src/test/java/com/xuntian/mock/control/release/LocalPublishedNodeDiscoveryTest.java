package com.xuntian.mock.control.release;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalPublishedNodeDiscoveryTest {
    @Test
    @SuppressWarnings("unchecked")
    void requiresCurrentHeartbeatForTheRequestedAppAndTestEnvironment() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var discovery = new LocalPublishedNodeDiscovery(redis, "node-1", "127.0.0.1");
        assertThatThrownBy(() -> discovery.registeredReadyNodes("TEST", "app")).hasMessageContaining("heartbeat");
        when(values.get("mock:local-runtime:TEST:app:node-1")).thenReturn("READY");
        assertThat(discovery.registeredReadyNodes("TEST", "app"))
                .containsExactly(new RuntimeNodeDiscoveryPort.RuntimeNode("node-1"));
        assertThatThrownBy(() -> discovery.registeredReadyNodes("TEST", "other")).hasMessageContaining("heartbeat");
        assertThatThrownBy(() -> discovery.registeredReadyNodes("PROD", "app")).hasMessageContaining("heartbeat");
        assertThatThrownBy(() -> new LocalPublishedNodeDiscovery(redis, "node-1", "0.0.0.0"))
                .hasMessageContaining("loopback");
    }
}
