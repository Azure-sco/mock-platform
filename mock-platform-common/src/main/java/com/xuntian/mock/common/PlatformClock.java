package com.xuntian.mock.common;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class PlatformClock {

    private final Clock clock;

    public PlatformClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PlatformClock systemUtc() {
        return new PlatformClock(Clock.systemUTC());
    }

    public Instant now() {
        return clock.instant();
    }
}
