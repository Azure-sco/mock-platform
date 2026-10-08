package com.xuntian.mock.runtime.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.runtime.RuntimeProperties;
import com.xuntian.mock.runtime.release.LocalActiveReleaseRegistry;
import com.xuntian.mock.runtime.release.ReleaseRecoveryPort;
import com.xuntian.mock.runtime.release.ReleaseSnapshotCache;
import com.xuntian.mock.runtime.release.RuntimeSnapshotEnvelopeVerifier;
import com.xuntian.mock.runtime.release.SnapshotSignatureKeyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LocalPublishedProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RuntimeProperties.class)
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(LocalActiveReleaseRegistry.class, () -> new LocalActiveReleaseRegistry(Duration.ofMinutes(1)))
            .withBean(ReleaseRecoveryPort.class, () -> mock(ReleaseRecoveryPort.class))
            .withBean(SnapshotSignatureKeyProvider.class, () -> keyId -> java.util.Optional.empty())
            .withBean(ReleaseSnapshotCache.class)
            .withUserConfiguration(RuntimeSnapshotEnvelopeVerifier.class, RuntimeSnapshotCompiler.class, FixtureRuntimeSnapshotRepository.class,
                    PublishedRuntimeSnapshotRepository.class);

    @Test
    void localFixtureModeRemainsCompatible() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RuntimeSnapshotRepository.class);
                    assertThat(context.getBean(RuntimeSnapshotRepository.class)).isInstanceOf(FixtureRuntimeSnapshotRepository.class);
                    assertThat(context.getBean(RuntimeSnapshotRepository.class).find("TEST", "sample-jdk17")).isPresent();
                });
    }

    @Test
    void localPublicationNeverFallsBackToFixtureBeforeAReleaseIsLoaded() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "local-published"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RuntimeSnapshotRepository.class);
                    var repository = context.getBean(RuntimeSnapshotRepository.class);
                    assertThat(repository).isInstanceOf(PublishedRuntimeSnapshotRepository.class);
                    assertThat(repository.find("TEST", "sample-jdk17")).isEmpty();
                    assertThatThrownBy(() -> repository.requirePinned("TEST", "sample-jdk17", Instant.now()))
                            .hasMessageContaining("No valid published Runtime Snapshot");
                });
    }
}
