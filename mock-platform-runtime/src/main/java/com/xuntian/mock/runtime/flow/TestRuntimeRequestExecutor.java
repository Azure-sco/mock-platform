package com.xuntian.mock.runtime.flow;

import com.xuntian.mock.runtime.engine.ContractScenarioEngine;
import com.xuntian.mock.runtime.engine.RuntimeRequest;
import com.xuntian.mock.runtime.release.PinnedRuntimeSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Component
@Profile("test")
public final class TestRuntimeRequestExecutor implements RuntimeRequestExecutor {

    private final ContractScenarioEngine engine;

    public TestRuntimeRequestExecutor(ContractScenarioEngine engine) {
        this.engine = engine;
    }

    @Override
    public Mono<ExecutedResponse> execute(
            PinnedRuntimeSnapshot pinned,
            RuntimeRequest request,
            Instant requestTime,
            UUID requestUuid) {
        return Mono.fromSupplier(() -> new ExecutedResponse(
                engine.execute(pinned.snapshot(), request, requestTime, requestUuid),
                pinned.activationVersion(), false));
    }
}
