package com.xuntian.mock.control.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"local", "test"})
public final class LocalConfigPublisherAdapter implements ConfigPublisherAdapter {

    private final Map<String, PublishedConfig> published = new ConcurrentHashMap<>();

    @Override
    public void publish(
            String targetType,
            String targetNamespace,
            String aggregateId,
            byte[] canonicalPayload,
            String checksum) {
        published.put(key(targetType, targetNamespace),
                new PublishedConfig(aggregateId, Arrays.copyOf(canonicalPayload, canonicalPayload.length), checksum));
    }

    PublishedConfig current(String targetType, String targetNamespace) {
        return published.get(key(targetType, targetNamespace));
    }

    private String key(String targetType, String targetNamespace) {
        return targetType + ":" + targetNamespace;
    }

    record PublishedConfig(String aggregateId, byte[] canonicalPayload, String checksum) {
    }
}
