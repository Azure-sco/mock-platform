package com.xuntian.mock.common;

import java.util.UUID;

public final class RequestIds {

    private RequestIds() {
    }

    public static String generate() {
        return "req-" + UUID.randomUUID();
    }
}
