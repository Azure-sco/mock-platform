package com.xuntian.mock.common;

import java.util.Arrays;
import java.util.stream.Collectors;

public final class RedisKeys {

    public static final String PREFIX = "third-party-mock:";

    private RedisKeys() {
    }

    public static String key(String firstSegment, String... remainingSegments) {
        String suffix = Arrays.stream(remainingSegments).collect(Collectors.joining(":"));
        return PREFIX + firstSegment + (suffix.isEmpty() ? "" : ":" + suffix);
    }
}
