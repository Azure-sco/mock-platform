package com.xuntian.mock.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableConfigurationProperties(RuntimeProperties.class)
@EnableScheduling
public class MockRuntimeApplication {

    @Bean
    ReactiveWebServerFactory reactiveWebServerFactory() {
        return new NettyReactiveWebServerFactory();
    }

    @Bean
    Clock runtimeClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(MockRuntimeApplication.class, args);
    }
}
