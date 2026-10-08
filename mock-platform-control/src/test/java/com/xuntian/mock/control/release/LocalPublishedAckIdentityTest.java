package com.xuntian.mock.control.release;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalPublishedAckIdentityTest {
    @Test
    void localPublicationRejectsLegacyTestSignatureHttpAcks() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "local-published"))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(LocalRuntimeAckIdentityVerifier.class, FailClosedRuntimeAckIdentityVerifier.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RuntimeAckIdentityVerifier.class);
                    var request = new MockHttpServletRequest();
                    request.addHeader("X-Mock-Runtime-Service", "runtime-local-1");
                    request.addHeader("X-Mock-Runtime-Timestamp", String.valueOf(System.currentTimeMillis()));
                    request.addHeader("X-Mock-Runtime-Nonce", "test-nonce");
                    request.addHeader("X-Mock-Runtime-Signature", "local-test-signature");
                    assertThatThrownBy(() -> context.getBean(RuntimeAckIdentityVerifier.class).verify(request))
                            .hasMessageContaining("verifier is not configured");
                });
    }
}
