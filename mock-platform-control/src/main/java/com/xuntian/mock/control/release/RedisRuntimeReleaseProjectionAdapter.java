package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Base64;

@Component
@Profile("!local & !test")
public final class RedisRuntimeReleaseProjectionAdapter implements RuntimeReleaseProjectionPort {

    private final StringRedisTemplate redis;

    public RedisRuntimeReleaseProjectionAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void putImmutableSnapshot(String releaseId, byte[] envelopeBytes) {
        String key = LocalRuntimeReleaseProjectionAdapter.snapshotKey(releaseId);
        String encoded = Base64.getEncoder().encodeToString(envelopeBytes);
        Boolean inserted = redis.opsForValue().setIfAbsent(key, encoded);
        if (!Boolean.TRUE.equals(inserted)) {
            byte[] existing = readImmutableSnapshot(releaseId);
            if (!Arrays.equals(existing, envelopeBytes)) {
                throw new PlatformException(ErrorCode.CONFLICT, "Immutable Runtime Snapshot key already has different bytes");
            }
        }
    }

    @Override
    public byte[] readImmutableSnapshot(String releaseId) {
        String value = redis.opsForValue().get(LocalRuntimeReleaseProjectionAdapter.snapshotKey(releaseId));
        return value == null ? null : Base64.getDecoder().decode(value);
    }

    @Override
    public void writeActivePointer(
            String environment, String app, long activationVersion, byte[] pointerBytes) {
        String script = """
                local current = redis.call('GET', KEYS[1])
                if current then
                  local ok, decoded = pcall(cjson.decode, current)
                  if ok and tonumber(decoded.activationVersion) > tonumber(ARGV[2]) then
                    return 0
                  end
                end
                redis.call('SET', KEYS[1], ARGV[1])
                return 1
                """;
        redis.execute(
                new DefaultRedisScript<>(script, Long.class),
                java.util.List.of(LocalRuntimeReleaseProjectionAdapter.pointerKey(environment, app)),
                new String(pointerBytes, java.nio.charset.StandardCharsets.UTF_8),
                String.valueOf(activationVersion));
        redis.convertAndSend(
                "mock:release-invalidate",
                environment + ":" + app + ":" + activationVersion);
    }
}
