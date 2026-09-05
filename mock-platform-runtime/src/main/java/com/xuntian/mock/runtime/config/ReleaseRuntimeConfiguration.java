package com.xuntian.mock.runtime.config;

import com.xuntian.mock.runtime.RuntimeProperties;
import com.xuntian.mock.runtime.release.LocalActiveReleaseRegistry;
import com.xuntian.mock.runtime.release.ReleaseSnapshotCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
public class ReleaseRuntimeConfiguration {

    @Bean
    ReleaseSnapshotCache releaseSnapshotCache() {
        return new ReleaseSnapshotCache();
    }

    @Bean
    LocalActiveReleaseRegistry localActiveReleaseRegistry(RuntimeProperties properties) {
        return new LocalActiveReleaseRegistry(properties.getLastKnownGoodWindow());
    }

    @Bean(destroyMethod = "dispose")
    Scheduler runtimeReleaseRefreshScheduler() {
        return Schedulers.newBoundedElastic(2, 100, "mock-runtime-release-refresh");
    }
}
