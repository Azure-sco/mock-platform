package com.xuntian.mock.control.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public final class ReleaseOutboxProjector {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseOutboxProjector.class);
    private final String workerId = "release-projector-" + UUID.randomUUID();
    private final ReleaseMapper mapper;
    private final ReleaseTransactionService transactions;
    private final RuntimeReleaseProjectionPort projection;

    public ReleaseOutboxProjector(
            ReleaseMapper mapper,
            ReleaseTransactionService transactions,
            RuntimeReleaseProjectionPort projection) {
        this.mapper = mapper;
        this.transactions = transactions;
        this.projection = projection;
    }

    @Scheduled(fixedDelayString = "${mock.release.outbox.fixed-delay-ms:250}")
    public void projectOne() {
        ReleaseOutboxRecord outbox = transactions.claimOutbox(workerId, Duration.ofSeconds(15));
        if (outbox == null) return;
        try {
            ReleaseActivationRecord activation = mapper.selectActivation(outbox.activationId());
            if (activation == null) throw new IllegalStateException("Release Activation is missing");
            projection.writeActivePointer(
                    activation.environment(), activation.appCode(),
                    outbox.activationVersion(), outbox.payloadBytes());
            transactions.finishProjection(outbox.id(), workerId, outbox.fencingToken());
        } catch (RuntimeException failure) {
            transactions.failProjection(outbox.id(), workerId, outbox.fencingToken(), failure);
            LOGGER.error(
                    "Release Outbox projection failed outboxId={} activationId={} attempt={}",
                    outbox.id(), outbox.activationId(), outbox.attemptCount());
        }
    }
}
