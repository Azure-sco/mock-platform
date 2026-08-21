package com.xuntian.mock.client.core.failure;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackDeciderTest {

    @Test
    void allowsRealFallbackOnlyForApprovedNonProductionReplayablePreConnectFailure() {
        assertThat(FallbackDecider.mayFallbackReal(
                "TEST", "real.example.com", Collections.singleton("real.example.com"), true,
                FailureClassification.BEFORE_CONNECT)).isTrue();

        assertThat(FallbackDecider.mayFallbackReal(
                "PROD", "real.example.com", Collections.singleton("real.example.com"), true,
                FailureClassification.BEFORE_CONNECT)).isFalse();
        assertThat(FallbackDecider.mayFallbackReal(
                "TEST", "real.example.com", Collections.singleton("real.example.com"), true,
                FailureClassification.POSSIBLY_DELIVERED)).isFalse();
        assertThat(FallbackDecider.mayFallbackReal(
                "TEST", "other.example.com", Collections.singleton("real.example.com"), true,
                FailureClassification.BEFORE_CONNECT)).isFalse();
        assertThat(FallbackDecider.mayFallbackReal(
                "TEST", "real.example.com", Collections.singleton("real.example.com"), false,
                FailureClassification.BEFORE_CONNECT)).isFalse();
    }
}
