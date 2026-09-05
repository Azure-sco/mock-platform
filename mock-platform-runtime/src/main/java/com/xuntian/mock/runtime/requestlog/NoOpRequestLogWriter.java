package com.xuntian.mock.runtime.requestlog;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Profile("test")
public final class NoOpRequestLogWriter implements RequestLogWriter {
    @Override
    public Mono<Void> write(RequestLogEntry entry) {
        return Mono.empty();
    }
}
