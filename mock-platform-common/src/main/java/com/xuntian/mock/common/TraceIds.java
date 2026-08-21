package com.xuntian.mock.common;

import java.util.UUID;

public final class TraceIds {

    private TraceIds() {
    }

    public static String generate() {
        return "trace-" + UUID.randomUUID();
    }
}
