package com.xuntian.mock.runtime.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "xuntian.mock.runtime.execution-cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class RequestExecutionCleanupWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestExecutionCleanupWorker.class);
    private final JdbcFlowStore store;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RequestExecutionCleanupWorker(
            JdbcFlowStore store,
            TransactionTemplate transaction,
            Clock clock) {
        this.store = store;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${xuntian.mock.runtime.execution-cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${xuntian.mock.runtime.execution-cleanup.fixed-delay-ms:60000}")
    public void cleanBatch() {
        Instant now = clock.instant();
        int deleted = 0;
        for (Long id : store.findExpiredExecutionIds(now, 100)) {
            Boolean removed = transaction.execute(status -> store.deleteExpiredExecution(id, now));
            if (Boolean.TRUE.equals(removed)) deleted++;
        }
        if (deleted > 0) LOGGER.info("Expired Request Executions deleted count={}", deleted);
    }
}
