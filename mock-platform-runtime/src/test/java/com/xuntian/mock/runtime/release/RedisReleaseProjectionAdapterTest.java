package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisReleaseProjectionAdapterTest {

    @Test
    void loadsFixedKeyContractAndRebuildsSnapshotBeforePointer() throws Exception {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReleaseScope scope = new ReleaseScope("TEST", "app-one");
        ActiveReleasePointer pointer = new ActiveReleasePointer(
                "rel-one", 3, "a".repeat(64), "key-one");
        String pointerJson = "{\"activationVersion\":3,\"releaseId\":\"rel-one\","
                + "\"signatureKeyId\":\"key-one\",\"snapshotChecksum\":\"" + "a".repeat(64) + "\"}";
        when(values.get("mock:active-release:TEST:app-one")).thenReturn(Mono.just(pointerJson));
        when(values.get("mock:release-snapshot:rel-one")).thenReturn(Mono.just("{\"envelope\":true}"));
        when(values.set(
                "mock:release-snapshot:rel-one",
                "{\"envelope\":true}",
                Duration.ofHours(24))).thenReturn(Mono.just(true));
        when(values.set("mock:active-release:TEST:app-one", pointerJson)).thenReturn(Mono.just(true));
        RuntimeProperties properties = new RuntimeProperties();
        RedisReleaseProjectionAdapter adapter = new RedisReleaseProjectionAdapter(
                redis, ReleaseTestData.mapper(), properties);

        assertThat(adapter.loadPointer(scope)).contains(pointer);
        assertThat(adapter.loadEnvelope("rel-one").orElseThrow())
                .isEqualTo("{\"envelope\":true}".getBytes(StandardCharsets.UTF_8));
        adapter.cacheRecovered(scope, new ReleaseCandidate(
                pointer, "{\"envelope\":true}".getBytes(StandardCharsets.UTF_8)));

        verify(values).set(
                "mock:release-snapshot:rel-one",
                "{\"envelope\":true}",
                Duration.ofHours(24));
        verify(values).set("mock:active-release:TEST:app-one", pointerJson);
    }
}
