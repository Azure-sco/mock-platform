package com.xuntian.mock.control.flow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "mock.flow.timer.enabled", havingValue = "true", matchIfMissing = true)
public final class FlowTimerWorker {

    private static final int MAX_BATCH = 100;
    private final FlowInstanceService service;

    public FlowTimerWorker(FlowInstanceService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${mock.flow.timer.fixed-delay-ms:250}")
    public void run() {
        for (int index = 0; index < MAX_BATCH && service.advanceOneDueTimer(); index++) {
            // Each call is an independent short transaction.
        }
    }
}
