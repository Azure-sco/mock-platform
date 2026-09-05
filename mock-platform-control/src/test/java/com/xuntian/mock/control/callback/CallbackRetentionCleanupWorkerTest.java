package com.xuntian.mock.control.callback;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackRetentionCleanupWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void deletesOnlyTasksThatAreStillExpiredAndTerminalUnderLock() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate();
        jdbc.candidates.addAll(List.of("expired", "retried", "fresh"));
        jdbc.rows.put("expired", row("SUCCESS", NOW.minusSeconds(1)));
        jdbc.rows.put("retried", row("RETRYING", NOW.minusSeconds(1)));
        jdbc.rows.put("fresh", row("FAILED", NOW.plusSeconds(1)));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        CallbackRetentionCleanupWorker worker = new CallbackRetentionCleanupWorker(
                jdbc, directTransactions(), Clock.fixed(NOW, ZoneOffset.UTC), 100, metrics);

        int deleted = worker.cleanupNow();

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.deletedAttempts).containsExactly("expired");
        assertThat(jdbc.deletedTasks).containsExactly("expired");
        assertThat(metrics.counter("mock.callback.retention.deleted").count()).isEqualTo(1);
    }

    @Test
    void rejectsAnUnboundedBatchSize() {
        assertThatThrownBy(() -> new CallbackRetentionCleanupWorker(
                new FakeJdbcTemplate(), directTransactions(), Clock.systemUTC(), 1001,
                new SimpleMeterRegistry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }

    private static Map<String, Object> row(String status, Instant expireAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("status", status);
        row.put("expire_at", Timestamp.from(expireAt));
        return row;
    }

    private static TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> callback) {
                return callback.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private final List<String> candidates = new ArrayList<>();
        private final Map<String, Map<String, Object>> rows = new HashMap<>();
        private final List<String> deletedAttempts = new ArrayList<>();
        private final List<String> deletedTasks = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            return (List<T>) List.copyOf(candidates);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            Map<String, Object> row = rows.get(String.valueOf(args[0]));
            return row == null ? List.of() : List.of(row);
        }

        @Override
        public int update(String sql, Object... args) {
            String taskId = String.valueOf(args[0]);
            if (sql.contains("mock_callback_attempt")) {
                deletedAttempts.add(taskId);
                return 1;
            }
            deletedTasks.add(taskId);
            rows.remove(taskId);
            return 1;
        }
    }
}
