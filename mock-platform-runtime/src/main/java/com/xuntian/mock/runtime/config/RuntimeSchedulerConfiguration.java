package com.xuntian.mock.runtime.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
public class RuntimeSchedulerConfiguration {

    @Bean(destroyMethod = "dispose")
    public Scheduler runtimeJdbcScheduler() {
        return Schedulers.newBoundedElastic(8, 1000, "mock-runtime-jdbc");
    }
}
