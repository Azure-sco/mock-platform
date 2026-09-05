package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"local", "test"})
public final class LocalRuntimeReleaseProjectionAdapter implements RuntimeReleaseProjectionPort {

    private final Map<String, byte[]> values = new ConcurrentHashMap<>();
    private final Map<String, Long> pointerVersions = new ConcurrentHashMap<>();

    @Override
    public void putImmutableSnapshot(String releaseId, byte[] envelopeBytes) {
        String key = snapshotKey(releaseId);
        byte[] previous = values.putIfAbsent(key, envelopeBytes.clone());
        if (previous != null && !Arrays.equals(previous, envelopeBytes)) {
            throw new PlatformException(ErrorCode.CONFLICT, "Immutable Runtime Snapshot key already has different bytes");
        }
    }

    @Override
    public byte[] readImmutableSnapshot(String releaseId) {
        byte[] value = values.get(snapshotKey(releaseId));
        return value == null ? null : value.clone();
    }

    @Override
    public synchronized void writeActivePointer(
            String environment, String app, long activationVersion, byte[] pointerBytes) {
        String key = pointerKey(environment, app);
        long current = pointerVersions.getOrDefault(key, -1L);
        if (activationVersion >= current) {
            values.put(key, pointerBytes.clone());
            pointerVersions.put(key, activationVersion);
        }
    }

    static String snapshotKey(String releaseId) {
        return "mock:release-snapshot:" + releaseId;
    }

    static String pointerKey(String environment, String app) {
        return "mock:active-release:" + environment + ":" + app;
    }
}
