package com.xuntian.mock.runtime.flow;

import com.xuntian.mock.runtime.engine.RuntimeExecution;
import com.xuntian.mock.runtime.engine.RuntimeRequest;
import com.xuntian.mock.runtime.release.PinnedRuntimeSnapshot;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface RuntimeRequestExecutor {

    Mono<ExecutedResponse> execute(
            PinnedRuntimeSnapshot pinned,
            RuntimeRequest request,
            Instant requestTime,
            UUID requestUuid);

    record ExecutedResponse(RuntimeExecution execution, long activationVersion, boolean replayed) { }
}
