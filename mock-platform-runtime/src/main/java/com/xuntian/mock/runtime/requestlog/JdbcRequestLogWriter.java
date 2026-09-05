package com.xuntian.mock.runtime.requestlog;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.temporal.ChronoUnit;

@Component
@Profile("!test")
public final class JdbcRequestLogWriter implements RequestLogWriter {

    private static final String INSERT = """
            INSERT INTO mock_request_log (
                id, mock_request_id, trace_id, environment, app_code, tenant_code,
                test_account_masked, provider_code, api_code, scenario_id, scenario_version_id,
                release_id, flow_key, business_no_hmac, hmac_key_version, http_method, path,
                request_summary, response_summary, http_status, match_result, duration_ms,
                error_code, expire_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPSERT_METRIC = """
            INSERT INTO mock_request_metric_minute (
                bucket_start, request_count, matched_count, no_match_count,
                latency_0_5_count, latency_6_10_count, latency_11_25_count,
                latency_26_50_count, latency_51_100_count, latency_101_250_count,
                latency_251_500_count, latency_501_1000_count, latency_1001_3000_count,
                latency_over_3000_count, max_duration_ms
            ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                request_count = request_count + VALUES(request_count),
                matched_count = matched_count + VALUES(matched_count),
                no_match_count = no_match_count + VALUES(no_match_count),
                latency_0_5_count = latency_0_5_count + VALUES(latency_0_5_count),
                latency_6_10_count = latency_6_10_count + VALUES(latency_6_10_count),
                latency_11_25_count = latency_11_25_count + VALUES(latency_11_25_count),
                latency_26_50_count = latency_26_50_count + VALUES(latency_26_50_count),
                latency_51_100_count = latency_51_100_count + VALUES(latency_51_100_count),
                latency_101_250_count = latency_101_250_count + VALUES(latency_101_250_count),
                latency_251_500_count = latency_251_500_count + VALUES(latency_251_500_count),
                latency_501_1000_count = latency_501_1000_count + VALUES(latency_501_1000_count),
                latency_1001_3000_count = latency_1001_3000_count + VALUES(latency_1001_3000_count),
                latency_over_3000_count = latency_over_3000_count + VALUES(latency_over_3000_count),
                max_duration_ms = GREATEST(max_duration_ms, VALUES(max_duration_ms))
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Scheduler runtimeJdbcScheduler;
    private final TransactionOperations transaction;

    @Autowired
    public JdbcRequestLogWriter(
            JdbcTemplate jdbcTemplate,
            Scheduler runtimeJdbcScheduler,
            PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, runtimeJdbcScheduler, new TransactionTemplate(transactionManager));
    }

    JdbcRequestLogWriter(
            JdbcTemplate jdbcTemplate,
            Scheduler runtimeJdbcScheduler,
            TransactionOperations transaction) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeJdbcScheduler = runtimeJdbcScheduler;
        this.transaction = transaction;
    }

    @Override
    public Mono<Void> write(RequestLogEntry entry) {
        return Mono.fromRunnable(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(INSERT, statement -> {
                    statement.setString(1, entry.id());
                    statement.setString(2, entry.mockRequestId());
                    statement.setString(3, entry.traceId());
                    statement.setString(4, entry.environment());
                    statement.setString(5, entry.appCode());
                    nullableString(statement, 6, entry.tenantCode());
                    nullableString(statement, 7, entry.testAccountMasked());
                    statement.setString(8, entry.providerCode());
                    statement.setString(9, entry.apiCode());
                    nullableString(statement, 10, entry.scenarioId());
                    nullableString(statement, 11, entry.scenarioVersionId());
                    nullableString(statement, 12, entry.releaseId());
                    statement.setString(13, entry.httpMethod());
                    statement.setString(14, entry.path());
                    statement.setString(15, entry.requestSummary());
                    statement.setString(16, entry.responseSummary());
                    if (entry.httpStatus() == null) statement.setNull(17, Types.SMALLINT);
                    else statement.setInt(17, entry.httpStatus());
                    statement.setString(18, entry.matchResult());
                    statement.setLong(19, entry.durationMs());
                    nullableString(statement, 20, entry.errorCode());
                    statement.setTimestamp(21, Timestamp.from(entry.expireAt()));
                    statement.setTimestamp(22, Timestamp.from(entry.createdAt()));
                });
            upsertMetric(entry);
        }))
                .subscribeOn(runtimeJdbcScheduler)
                .then();
    }

    private void upsertMetric(RequestLogEntry entry) {
        long duration = entry.durationMs();
        jdbcTemplate.update(UPSERT_METRIC,
                Timestamp.from(entry.createdAt().truncatedTo(ChronoUnit.MINUTES)),
                flag("MATCHED".equals(entry.matchResult())),
                flag("MOCK_NO_MATCH".equals(entry.errorCode())),
                flag(duration <= 5),
                flag(duration >= 6 && duration <= 10),
                flag(duration >= 11 && duration <= 25),
                flag(duration >= 26 && duration <= 50),
                flag(duration >= 51 && duration <= 100),
                flag(duration >= 101 && duration <= 250),
                flag(duration >= 251 && duration <= 500),
                flag(duration >= 501 && duration <= 1000),
                flag(duration >= 1001 && duration <= 3000),
                flag(duration > 3000),
                duration);
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static void nullableString(java.sql.PreparedStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }
}
