package com.xuntian.mock.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformClockTest {

    @Test
    void canBeFixedForDeterministicPlatformLogic() {
        Instant expected = Instant.parse("2026-08-20T00:00:00Z");
        PlatformClock clock = new PlatformClock(Clock.fixed(expected, ZoneOffset.UTC));

        assertThat(clock.now()).isEqualTo(expected);
    }
}
