package com.xuntian.mock.runtime.requestlog;

import reactor.core.publisher.Mono;

public interface RequestLogWriter {
    Mono<Void> write(RequestLogEntry entry);
}
