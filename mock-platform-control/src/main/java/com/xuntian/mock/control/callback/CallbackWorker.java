package com.xuntian.mock.control.callback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "mock.callback.worker.enabled", havingValue = "true", matchIfMissing = true)
public final class CallbackWorker {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackWorker.class);
    private final CallbackWorkerTransactions transactions;
    private final CallbackDispatcher dispatcher;
    private final Clock clock;
    private final String workerId;
    private final int batchSize;

    public CallbackWorker(
            CallbackWorkerTransactions transactions,
            CallbackDispatcher dispatcher,
            Clock clock,
            @Value("${mock.callback.worker-id:${HOSTNAME:callback-worker-local}}") String workerId,
            @Value("${mock.callback.batch-size:20}") int batchSize) {
        this.transactions = transactions;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.workerId = workerId;
        this.batchSize = Math.max(1, Math.min(100, batchSize));
    }

    @Scheduled(fixedDelayString = "${mock.callback.fixed-delay-ms:250}")
    public void runOnce() {
        Instant now = clock.instant();
        for (CallbackWorkerTransactions.ClaimedTask claim : transactions.claim(workerId, now, batchSize)) {
            process(claim);
        }
    }

    private void process(CallbackWorkerTransactions.ClaimedTask claim) {
        CallbackDispatcher.PreparedCallback prepared;
        try {
            prepared = dispatcher.prepare(claim.task());
        } catch (CallbackDispatcher.PreparationException failure) {
            transactions.preparationFailed(claim, mask(failure), clock.instant());
            return;
        }
        boolean flowAccepts = transactions.flowAccepts(claim);
        if (!transactions.start(claim, flowAccepts, clock.instant())) return;
        CallbackDispatcher.SendOutcome outcome;
        try {
            outcome = dispatcher.send(prepared);
        } catch (RuntimeException failure) {
            outcome = CallbackDispatcher.SendOutcome.unknown(mask(failure), 0);
        }
        try {
            transactions.finalizeSend(claim, outcome, clock.instant());
        } catch (RuntimeException failure) {
            // STARTED remains recoverable; the next lease owner marks it ABANDONED/UNKNOWN.
            LOG.error("Callback finalize failed: taskId={}, fence={}",
                    claim.task().taskId(), claim.fencingToken(), failure);
        }
    }

    private static String mask(Throwable failure) {
        String value = failure.getClass().getSimpleName();
        return value.length() > 128 ? "Callback failure" : value;
    }
}
