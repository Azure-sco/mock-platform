package com.xuntian.mock.runtime.requestlog;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRequestLogWriterTest {

    @Test
    void alwaysRunsBlockingJdbcWorkOnBoundedScheduler() {
        AtomicReference<String> thread = new AtomicReference<>();
        JdbcTemplate template = new JdbcTemplate() {
            @Override
            public int update(String sql, PreparedStatementSetter setter) {
                thread.set(Thread.currentThread().getName());
                return 1;
            }

            @Override
            public int update(String sql, Object... args) {
                thread.set(Thread.currentThread().getName());
                return 1;
            }
        };
        Scheduler scheduler = Schedulers.newBoundedElastic(1, 10, "runtime-jdbc-test");
        try {
            new JdbcRequestLogWriter(template, scheduler, directTransactions())
                    .write(entry()).block(Duration.ofSeconds(5));
        } finally {
            scheduler.dispose();
        }

        assertThat(thread.get()).startsWith("runtime-jdbc-test-");
    }

    private static TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> callback) {
                return callback.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    static RequestLogEntry entry() {
        Instant now = Instant.now();
        return new RequestLogEntry(
                "log-1", "mr-1", "trace-1", "TEST", "app", null, null,
                "P", "A", "sc-1", "scv-1", "rel-1", "POST", "/path",
                "{\"bodyBytes\":0}", "{\"bodyBytes\":2}", 200, "MATCHED", 4,
                null, now.plusSeconds(86400), now);
    }
}
