package com.xuntian.mock.client.config;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reports ACKs away from the configuration listener thread and retries the same idempotent event a
 * bounded number of times. Failures never change or block the already selected routing snapshot.
 */
public final class RetryingSdkConfigAckReporter implements SdkConfigAckReporter {

    private final SdkConfigAckPort port;
    private final ScheduledExecutorService executor;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final AtomicLong exhaustedReports = new AtomicLong();

    public RetryingSdkConfigAckReporter(
            SdkConfigAckPort port,
            ScheduledExecutorService executor,
            int maxAttempts,
            long retryDelayMillis) {
        this.port = Objects.requireNonNull(port, "port");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        if (retryDelayMillis < 1L || retryDelayMillis > 60_000L) {
            throw new IllegalArgumentException("retryDelayMillis is out of range");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
    }

    @Override
    public void report(SdkConfigAck acknowledgement) {
        Objects.requireNonNull(acknowledgement, "acknowledgement");
        executor.execute(new Attempt(acknowledgement, 1));
    }

    public long exhaustedReportCount() {
        return exhaustedReports.get();
    }

    private final class Attempt implements Runnable {
        private final SdkConfigAck acknowledgement;
        private final int attempt;

        private Attempt(SdkConfigAck acknowledgement, int attempt) {
            this.acknowledgement = acknowledgement;
            this.attempt = attempt;
        }

        @Override
        public void run() {
            try {
                port.report(acknowledgement);
            } catch (Exception failure) {
                if (attempt < maxAttempts) {
                    try {
                        executor.schedule(
                                new Attempt(acknowledgement, attempt + 1),
                                retryDelay(attempt),
                                TimeUnit.MILLISECONDS);
                    } catch (RuntimeException schedulingFailure) {
                        exhaustedReports.incrementAndGet();
                    }
                } else {
                    exhaustedReports.incrementAndGet();
                }
            }
        }
    }

    private long retryDelay(int failedAttempt) {
        long multiplier = 1L << Math.min(failedAttempt - 1, 9);
        return Math.min(60_000L, retryDelayMillis * multiplier);
    }
}
