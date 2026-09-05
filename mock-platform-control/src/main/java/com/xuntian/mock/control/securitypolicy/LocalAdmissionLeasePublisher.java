package com.xuntian.mock.control.securitypolicy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"local", "test"})
public final class LocalAdmissionLeasePublisher implements AdmissionLeasePublisher {

    private static final String KEY_PREFIX = "mock:active-admission:";

    private static final DefaultRedisScript<Long> REPLACE_IF_NEWER = new DefaultRedisScript<>("""
            local current = redis.call('HMGET', KEYS[2], 'version', 'issuedAt')
            if current[1] then
              local currentVersion = tonumber(current[1])
              local currentIssuedAt = tonumber(current[2])
              local nextVersion = tonumber(ARGV[1])
              local nextIssuedAt = tonumber(ARGV[2])
              if currentVersion > nextVersion or (currentVersion == nextVersion and currentIssuedAt >= nextIssuedAt) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])
            redis.call('HSET', KEYS[2], 'version', ARGV[1], 'issuedAt', ARGV[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final Map<String, InMemoryLease> inMemory = new ConcurrentHashMap<>();

    public LocalAdmissionLeasePublisher(ObjectProvider<StringRedisTemplate> redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate.getIfAvailable();
        this.clock = clock;
    }

    @Override
    public boolean publishIfNewer(
            String environment,
            String appCode,
            long bindingVersion,
            Instant issuedAt,
            Instant notAfter,
            byte[] canonicalEnvelope) {
        String key = KEY_PREFIX + environment + ":" + appCode;
        long ttlMillis = Duration.between(clock.instant(), notAfter).toMillis();
        if (ttlMillis <= 0) {
            return false;
        }
        if (redisTemplate == null) {
            return inMemory.compute(key, (ignored, current) -> {
                if (current != null && (current.bindingVersion() > bindingVersion
                        || (current.bindingVersion() == bindingVersion && !current.issuedAt().isBefore(issuedAt)))) {
                    return current;
                }
                return new InMemoryLease(bindingVersion, issuedAt, canonicalEnvelope.clone());
            }).bindingVersion() == bindingVersion;
        }
        Long result = redisTemplate.execute(
                REPLACE_IF_NEWER,
                List.of(key, key + ":meta"),
                Long.toString(bindingVersion),
                Long.toString(issuedAt.toEpochMilli()),
                Base64.getEncoder().encodeToString(canonicalEnvelope),
                Long.toString(ttlMillis));
        return Long.valueOf(1L).equals(result);
    }

    record InMemoryLease(long bindingVersion, Instant issuedAt, byte[] payload) {
    }
}
