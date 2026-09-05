package com.xuntian.mock.runtime.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xuntian.mock.runtime.RuntimeProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
@Profile("!local & !test")
public final class RedisReleaseProjectionAdapter implements ReleaseProjectionPort {

    static final String ACTIVE_PREFIX = "mock:active-release:";
    static final String SNAPSHOT_PREFIX = "mock:release-snapshot:";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RuntimeProperties properties;

    public RedisReleaseProjectionAdapter(
            ReactiveStringRedisTemplate redis,
            ObjectMapper mapper,
            RuntimeProperties properties) {
        this.redis = redis;
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.properties = properties;
    }

    @Override
    public Optional<ActiveReleasePointer> loadPointer(ReleaseScope scope) {
        String value = redis.opsForValue()
                .get(pointerKey(scope))
                .block(properties.getReleaseSourceTimeout());
        if (value == null) {
            return Optional.empty();
        }
        try {
            ActiveReleasePointer pointer = mapper.readValue(value, ActiveReleasePointer.class);
            if (!value.equals(mapper.writeValueAsString(pointer))) {
                throw new IllegalArgumentException("Active Release Pointer is not canonical JSON");
            }
            return Optional.of(pointer);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new SnapshotVerificationException(
                    SnapshotVerificationException.Reason.POINTER_INVALID,
                    "Redis Active Release Pointer is invalid",
                    failure);
        }
    }

    @Override
    public Optional<byte[]> loadEnvelope(String releaseId) {
        String value = redis.opsForValue()
                .get(snapshotKey(releaseId))
                .block(properties.getReleaseSourceTimeout());
        return value == null
                ? Optional.empty()
                : Optional.of(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void cacheRecovered(ReleaseScope scope, ReleaseCandidate candidate) {
        String envelope = new String(candidate.envelopeBytes(), StandardCharsets.UTF_8);
        Boolean snapshotWritten = redis.opsForValue()
                .set(snapshotKey(candidate.pointer().releaseId()), envelope, properties.getReleaseSnapshotRedisTtl())
                .block(properties.getReleaseSourceTimeout());
        if (!Boolean.TRUE.equals(snapshotWritten)) {
            throw new IllegalStateException("Recovered Release Snapshot was not cached");
        }
        try {
            Boolean pointerWritten = redis.opsForValue()
                    .set(pointerKey(scope), mapper.writeValueAsString(candidate.pointer()))
                    .block(properties.getReleaseSourceTimeout());
            if (!Boolean.TRUE.equals(pointerWritten)) {
                throw new IllegalStateException("Recovered Active Release Pointer was not cached");
            }
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Recovered Active Release Pointer cannot be serialized", failure);
        }
    }

    static String pointerKey(ReleaseScope scope) {
        return ACTIVE_PREFIX + scope.environment() + ":" + scope.app();
    }

    static String snapshotKey(String releaseId) {
        return SNAPSHOT_PREFIX + releaseId;
    }
}
