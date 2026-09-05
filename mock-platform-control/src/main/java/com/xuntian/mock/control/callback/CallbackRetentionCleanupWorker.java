package com.xuntian.mock.control.callback;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "mock.callback.retention-cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class CallbackRetentionCleanupWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(CallbackRetentionCleanupWorker.class);
    private static final Set<String> TERMINAL = Set.of(
            "SUCCESS", "FAILED", "FAILED_PREPARATION", "FAILED_UNCONFIRMED", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final TransactionOperations transaction;
    private final Clock clock;
    private final int batchSize;
    private final Counter deletedCounter;
    private final Counter failureCounter;
    private final Timer durationTimer;

    @Autowired
    public CallbackRetentionCleanupWorker(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Environment environment,
            MeterRegistry meterRegistry) {
        this(
                jdbc,
                new TransactionTemplate(transactionManager),
                clock,
                integer(environment, "mock.callback.retention-cleanup.batch-size", 100),
                meterRegistry);
    }

    CallbackRetentionCleanupWorker(
            JdbcTemplate jdbc,
            TransactionOperations transaction,
            Clock clock,
            int batchSize,
            MeterRegistry meterRegistry) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Callback retention cleanup batch-size must be from 1 to 1000");
        }
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
        this.batchSize = batchSize;
        this.deletedCounter = meterRegistry.counter("mock.callback.retention.deleted");
        this.failureCounter = meterRegistry.counter("mock.callback.retention.failures");
        this.durationTimer = meterRegistry.timer("mock.callback.retention.duration");
    }

    @Scheduled(
            initialDelayString = "${mock.callback.retention-cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${mock.callback.retention-cleanup.fixed-delay-ms:60000}")
    public void scheduledCleanup() {
        try {
            int deleted = cleanupNow();
            if (deleted > 0) {
                LOGGER.info("Expired terminal Callback Tasks deleted count={}", deleted);
            }
        } catch (RuntimeException failure) {
            failureCounter.increment();
            LOGGER.error("Callback retention cleanup failed type={}", failure.getClass().getName(), failure);
        }
    }

    public int cleanupNow() {
        Timer.Sample sample = Timer.start();
        try {
            Instant now = clock.instant();
            List<String> taskIds = jdbc.queryForList("""
                    SELECT task_id
                      FROM mock_callback_task
                     WHERE expire_at <= ?
                       AND status IN ('SUCCESS','FAILED','FAILED_PREPARATION','FAILED_UNCONFIRMED','CANCELLED')
                     ORDER BY expire_at, id
                     LIMIT ?
                    """, String.class, Timestamp.from(now), batchSize);
            int deleted = 0;
            for (String taskId : taskIds) {
                Boolean removed = transaction.execute(status -> cleanupOne(taskId, now));
                if (Boolean.TRUE.equals(removed)) deleted++;
            }
            deletedCounter.increment(deleted);
            return deleted;
        } finally {
            sample.stop(durationTimer);
        }
    }

    private boolean cleanupOne(String taskId, Instant now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, expire_at
                  FROM mock_callback_task
                 WHERE task_id = ?
                 FOR UPDATE
                """, taskId);
        if (rows.isEmpty()) return false;
        Map<String, Object> row = rows.get(0);
        String status = String.valueOf(row.get("status"));
        Instant expireAt = ((Timestamp) row.get("expire_at")).toInstant();
        if (!TERMINAL.contains(status) || expireAt.isAfter(now)) return false;

        jdbc.update("DELETE FROM mock_callback_attempt WHERE task_id = ?", taskId);
        return jdbc.update("""
                DELETE FROM mock_callback_task
                 WHERE task_id = ? AND expire_at <= ?
                   AND status IN ('SUCCESS','FAILED','FAILED_PREPARATION','FAILED_UNCONFIRMED','CANCELLED')
                """, taskId, Timestamp.from(now)) == 1;
    }

    private static int integer(Environment environment, String key, int fallback) {
        String value = environment.getProperty(key);
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be an integer", invalid);
        }
    }
}
