package com.xuntian.mock.runtime.requestlog;

import com.xuntian.mock.runtime.RuntimeProperties;
import com.xuntian.mock.runtime.release.ActivationAck;
import com.xuntian.mock.runtime.release.ActiveReleasePointer;
import com.xuntian.mock.runtime.release.JdbcActivationAckAdapter;
import com.xuntian.mock.runtime.release.JdbcReleaseRecoveryAdapter;
import com.xuntian.mock.runtime.release.ReleaseCandidate;
import com.xuntian.mock.runtime.release.ReleaseScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class JdbcRequestLogWriterMySqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36");

    private static JdbcTemplate jdbc;
    private static Scheduler scheduler;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        scheduler = Schedulers.newBoundedElastic(1, 10, "runtime-jdbc-mysql-test");
        jdbc.execute("""
                CREATE TABLE mock_request_log (
                    id VARCHAR(64) NOT NULL,
                    mock_request_id VARCHAR(64) NOT NULL,
                    trace_id VARCHAR(64) NULL,
                    environment VARCHAR(32) NOT NULL,
                    app_code VARCHAR(128) NOT NULL,
                    tenant_code VARCHAR(128) NULL,
                    test_account_masked VARCHAR(256) NULL,
                    provider_code VARCHAR(64) NOT NULL,
                    api_code VARCHAR(64) NOT NULL,
                    scenario_id VARCHAR(64) NULL,
                    scenario_version_id VARCHAR(64) NULL,
                    release_id VARCHAR(64) NULL,
                    flow_key VARCHAR(128) NULL,
                    business_no_hmac CHAR(64) NULL,
                    hmac_key_version VARCHAR(32) NULL,
                    http_method VARCHAR(16) NOT NULL,
                    path VARCHAR(1024) NOT NULL,
                    request_summary VARCHAR(4096) NULL,
                    response_summary VARCHAR(4096) NULL,
                    http_status SMALLINT UNSIGNED NULL,
                    match_result VARCHAR(64) NOT NULL,
                    duration_ms BIGINT UNSIGNED NOT NULL,
                    error_code VARCHAR(64) NULL,
                    expire_at TIMESTAMP(6) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL,
                    PRIMARY KEY (created_at, id),
                    INDEX idx_request_log_id (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE mock_request_metric_minute (
                    bucket_start TIMESTAMP NOT NULL PRIMARY KEY,
                    request_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    matched_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    no_match_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_0_5_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_6_10_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_11_25_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_26_50_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_51_100_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_101_250_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_251_500_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_501_1000_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_1001_3000_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    latency_over_3000_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    max_duration_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    CONSTRAINT chk_metric_rollback_probe CHECK (max_duration_ms <> 999)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE mock_release (
                    id VARCHAR(64) PRIMARY KEY,
                    status VARCHAR(32) NOT NULL,
                    snapshot_bytes LONGBLOB NOT NULL,
                    checksum CHAR(64) NOT NULL,
                    signature_key_id VARCHAR(128) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE mock_active_release (
                    environment VARCHAR(32) NOT NULL,
                    app_code VARCHAR(128) NOT NULL,
                    release_id VARCHAR(64) NULL,
                    activation_version BIGINT UNSIGNED NOT NULL,
                    state VARCHAR(32) NOT NULL,
                    PRIMARY KEY (environment, app_code),
                    FOREIGN KEY (release_id) REFERENCES mock_release(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE mock_runtime_activation_ack (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    environment VARCHAR(32) NOT NULL,
                    app_code VARCHAR(128) NOT NULL,
                    runtime_node_id VARCHAR(128) NOT NULL,
                    release_id VARCHAR(64) NOT NULL,
                    activation_version BIGINT UNSIGNED NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    error_masked VARCHAR(512) NULL,
                    reported_at TIMESTAMP(6) NOT NULL,
                    UNIQUE KEY uk_runtime_ack (environment, app_code, runtime_node_id, activation_version),
                    FOREIGN KEY (release_id) REFERENCES mock_release(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @AfterAll
    static void disposeScheduler() {
        if (scheduler != null) {
            scheduler.dispose();
        }
    }

    @Test
    void insertsRequestLogIntoRealMySql() {
        RequestLogEntry entry = JdbcRequestLogWriterTest.entry();

        JdbcRequestLogWriter writer = new JdbcRequestLogWriter(jdbc, scheduler, transactionManager);
        writer.write(entry).block(Duration.ofSeconds(10));
        writer.write(new RequestLogEntry(
                "log-2", "mr-2", "trace-2", "TEST", "app", null, null,
                "P", "A", null, null, "rel-1", "POST", "/path",
                "{}", "{}", 404, "FAILED", 120, "MOCK_NO_MATCH",
                entry.expireAt(), entry.createdAt())).block(Duration.ofSeconds(10));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_request_log WHERE id = ? AND scenario_id = ?",
                Integer.class,
                entry.id(),
                entry.scenarioId())).isEqualTo(1);
        var metrics = jdbc.queryForMap(
                "SELECT request_count, matched_count, no_match_count, latency_0_5_count, "
                        + "latency_101_250_count, max_duration_ms FROM mock_request_metric_minute");
        assertThat(((Number) metrics.get("request_count")).longValue()).isEqualTo(2);
        assertThat(((Number) metrics.get("matched_count")).longValue()).isEqualTo(1);
        assertThat(((Number) metrics.get("no_match_count")).longValue()).isEqualTo(1);
        assertThat(((Number) metrics.get("latency_0_5_count")).longValue()).isEqualTo(1);
        assertThat(((Number) metrics.get("latency_101_250_count")).longValue()).isEqualTo(1);
        assertThat(((Number) metrics.get("max_duration_ms")).longValue()).isEqualTo(120);
    }

    @Test
    void rollsBackRequestLogWhenMetricUpsertFails() {
        RequestLogEntry base = JdbcRequestLogWriterTest.entry();
        RequestLogEntry entry = new RequestLogEntry(
                "log-rollback", "mr-rollback", "trace-rollback", "TEST", "app", null, null,
                "P", "A", "sc-1", "scv-1", "rel-1", "POST", "/path",
                "{}", "{}", 200, "MATCHED", 999, null,
                base.expireAt(), base.createdAt().plusSeconds(60));
        JdbcRequestLogWriter writer = new JdbcRequestLogWriter(jdbc, scheduler, transactionManager);

        assertThatThrownBy(() -> writer.write(entry).block(Duration.ofSeconds(10)))
                .hasMessageContaining("chk_metric_rollback_probe");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_request_log WHERE id = ?",
                Integer.class,
                entry.id())).isZero();
    }

    @Test
    void recoversPublishedReleaseAndUpsertsActivationAckInRealMySql() {
        String checksum = "a".repeat(64);
        byte[] envelope = "{\"signed\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.update(
                "INSERT INTO mock_release (id, status, snapshot_bytes, checksum, signature_key_id) VALUES (?, 'READY', ?, ?, ?)",
                "rel-jdbc", envelope, checksum, "key-1");
        jdbc.update(
                "INSERT INTO mock_active_release (environment, app_code, release_id, activation_version, state) VALUES (?, ?, ?, ?, 'ACTIVATING')",
                "TEST", "app-jdbc", "rel-jdbc", 5L);
        RuntimeProperties properties = new RuntimeProperties();
        properties.setReleaseSourceTimeout(Duration.ofSeconds(5));
        ReleaseScope scope = new ReleaseScope("TEST", "app-jdbc");

        ReleaseCandidate recovered = new JdbcReleaseRecoveryAdapter(jdbc, scheduler, properties)
                .recover(scope)
                .orElseThrow();

        assertThat(recovered.pointer()).isEqualTo(new ActiveReleasePointer(
                "rel-jdbc", 5, checksum, "key-1"));
        assertThat(recovered.envelopeBytes()).isEqualTo(envelope);

        JdbcActivationAckAdapter writer = new JdbcActivationAckAdapter(jdbc, scheduler, properties);
        writer.record(new ActivationAck(
                scope, "runtime-node-jdbc", "rel-jdbc", 5,
                ActivationAck.Status.FAILED, "SIGNATURE_INVALID", Instant.parse("2026-08-31T00:00:00Z")));
        writer.record(new ActivationAck(
                scope, "runtime-node-jdbc", "rel-jdbc", 5,
                ActivationAck.Status.READY, null, Instant.parse("2026-08-31T00:00:01Z")));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM mock_runtime_activation_ack WHERE environment = ? AND app_code = ? AND runtime_node_id = ? AND activation_version = ?",
                String.class, "TEST", "app-jdbc", "runtime-node-jdbc", 5L)).isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_runtime_activation_ack WHERE environment = ? AND app_code = ?",
                Integer.class, "TEST", "app-jdbc")).isEqualTo(1);
    }
}
