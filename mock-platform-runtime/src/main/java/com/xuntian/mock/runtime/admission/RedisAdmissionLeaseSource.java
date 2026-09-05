package com.xuntian.mock.runtime.admission;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Optional;

@Component
@Profile("!local & !test")
public final class RedisAdmissionLeaseSource implements AdmissionLeaseSource {

    private static final String KEY_PREFIX = "mock:active-admission:";
    private final ReactiveStringRedisTemplate redis;
    private final RuntimeProperties properties;

    public RedisAdmissionLeaseSource(ReactiveStringRedisTemplate redis, RuntimeProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Optional<byte[]> load(AdmissionScope scope) {
        String encoded = redis.opsForValue()
                .get(KEY_PREFIX + scope.environment() + ":" + scope.appCode())
                .block(properties.getReleaseSourceTimeout());
        if (encoded == null) return Optional.empty();
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length == 0 || decoded.length > AdmissionEnvelopeVerifier.MAX_ENVELOPE_BYTES) {
                throw new IllegalArgumentException("invalid size");
            }
            return Optional.of(decoded);
        } catch (IllegalArgumentException invalid) {
            throw new AdmissionVerificationException(
                    AdmissionVerificationException.Reason.INVALID_ENVELOPE,
                    "Redis Admission envelope is invalid", invalid);
        }
    }
}
