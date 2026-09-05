package com.xuntian.mock.control.release;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class UuidReleaseIdGenerator implements ReleaseIdGenerator {

    @Override
    public String nextReleaseId() {
        return "rel-" + UUID.randomUUID();
    }

    @Override
    public String nextActivationId() {
        return "act-" + UUID.randomUUID();
    }
}
