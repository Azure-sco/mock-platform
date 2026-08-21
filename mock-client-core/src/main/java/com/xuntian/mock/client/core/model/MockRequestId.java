package com.xuntian.mock.client.core.model;

import java.util.UUID;

public final class MockRequestId {

    private MockRequestId() {
    }

    public static String generate() {
        return "mr-" + UUID.randomUUID().toString();
    }
}
