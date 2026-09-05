package com.xuntian.mock.control.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class M2ControlConfiguration {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
