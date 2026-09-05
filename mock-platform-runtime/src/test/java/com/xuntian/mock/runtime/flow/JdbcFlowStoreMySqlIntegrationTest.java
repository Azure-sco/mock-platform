package com.xuntian.mock.runtime.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class JdbcFlowStoreMySqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private JdbcFlowStore store;

    @BeforeEach
    void resetSchema() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new JdbcFlowStore(jdbc, new ObjectMapper().findAndRegisterModules());
        jdbc.execute("DROP TABLE IF EXISTS mock_request_execution");
        jdbc.execute("DROP TABLE IF EXISTS mock_callback_task");
        jdbc.execute("DROP TABLE IF EXISTS mock_flow_instance");
        jdbc.execute("""
                CREATE TABLE mock_flow_instance (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    flow_key VARCHAR(128) NOT NULL,
                    environment VARCHAR(32) NOT NULL,
                    app_code VARCHAR(128) NOT NULL,
                    provider_code VARCHAR(64) NOT NULL,
                    flow_code VARCHAR(64) NOT NULL,
                    tenant_code VARCHAR(128) NOT NULL,
                    test_account VARCHAR(128) NOT NULL,
                    business_no_hmac CHAR(64) NOT NULL,
                    hmac_key_version VARCHAR(32) NOT NULL,
                    business_no_masked VARCHAR(256),
                    release_id VARCHAR(64) NOT NULL,
                    flow_definition_version_id BIGINT NOT NULL,
                    flow_definition_checksum CHAR(64) NOT NULL,
                    generation INT UNSIGNED NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    current_state VARCHAR(64) NOT NULL,
                    query_count BIGINT UNSIGNED NOT NULL,
                    variables_json JSON NOT NULL,
                    version BIGINT UNSIGNED NOT NULL,
                    pending_transition_id VARCHAR(128),
                    next_transition_at TIMESTAMP(6),
                    expire_at TIMESTAMP(6) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL,
                    UNIQUE KEY uk_flow_key (flow_key),
                    UNIQUE KEY uk_flow_business (
                        environment, app_code, provider_code, flow_code, tenant_code,
                        test_account, hmac_key_version, business_no_hmac)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE mock_request_execution (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    app_code VARCHAR(128) NOT NULL,
                    mock_request_id VARCHAR(64) NOT NULL,
                    execution_generation INT UNSIGNED NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    release_id VARCHAR(64),
                    activation_version BIGINT UNSIGNED,
                    scenario_version_id BIGINT,
                    flow_instance_id BIGINT,
                    flow_generation INT UNSIGNED,
                    transition_result_json JSON,
                    response_status SMALLINT UNSIGNED,
                    response_headers_encrypted MEDIUMBLOB,
                    response_body_encrypted MEDIUMBLOB,
                    fault_type VARCHAR(64),
                    fault_duration_ms BIGINT UNSIGNED,
                    side_effect_policy VARCHAR(32),
                    encryption_key_id VARCHAR(128),
                    expire_at TIMESTAMP(6) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL,
                    completed_at TIMESTAMP(6),
                    UNIQUE KEY uk_request (app_code, mock_request_id),
                    INDEX idx_expire (expire_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE mock_callback_task (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    flow_instance_id BIGINT NOT NULL,
                    flow_generation INT UNSIGNED NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    lease_owner VARCHAR(128),
                    lease_until TIMESTAMP(6),
                    last_error_masked VARCHAR(512),
                    updated_at TIMESTAMP(6) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Test
    void distinguishesInsertFromDuplicateAndReturnsTheLockedExistingFlow() {
        Instant now = Instant.parse("2026-08-31T08:00:00Z");
        FlowInstance proposed = flow(now);

        transaction.executeWithoutResult(status -> {
            JdbcFlowStore.InsertOrLockResult first = store.insertOrLock(proposed);
            JdbcFlowStore.InsertOrLockResult duplicate = store.insertOrLock(proposed);

            assertThat(first.inserted()).isTrue();
            assertThat(first.instance().id()).isPositive();
            assertThat(duplicate.inserted()).isFalse();
            assertThat(duplicate.instance().id()).isEqualTo(first.instance().id());
        });
    }

    @Test
    void reactivationCancelsPendingCallbacksButFailsWhenOneIsRunning() {
        Instant now = Instant.parse("2026-08-31T08:00:00Z");
        long flowId = transaction.execute(status -> store.insertOrLock(flow(now)).instance().id());
        jdbc.update("INSERT INTO mock_callback_task (flow_instance_id, flow_generation, status, updated_at) VALUES (?, 1, 'NEW', ?)", flowId, now);
        jdbc.update("INSERT INTO mock_callback_task (flow_instance_id, flow_generation, status, updated_at) VALUES (?, 1, 'RETRYING', ?)", flowId, now);

        transaction.executeWithoutResult(status ->
                store.cancelPendingCallbacksOrFailBusy(flowId, 1, now.plusSeconds(1)));

        assertThat(jdbc.queryForList(
                "SELECT status FROM mock_callback_task ORDER BY id", String.class))
                .containsExactly("CANCELLED", "CANCELLED");

        jdbc.update("INSERT INTO mock_callback_task (flow_instance_id, flow_generation, status, updated_at) VALUES (?, 1, 'RUNNING', ?)", flowId, now);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                store.cancelPendingCallbacksOrFailBusy(flowId, 1, now.plusSeconds(2))))
                .isInstanceOf(PlatformException.class)
                .extracting(failure -> ((PlatformException) failure).errorCode())
                .isEqualTo(ErrorCode.MOCK_FLOW_OPERATION_BUSY);
    }

    @Test
    void cleanupRechecksLockedExpiryAndCannotDeleteAReusedExecutionGeneration() {
        Instant created = Instant.parse("2026-08-31T08:00:00Z");
        JdbcFlowStore.RequestExecutionRecord first = transaction.execute(status ->
                store.lockExecution("app", "request-1", "a".repeat(64), created, created.plusSeconds(1)));
        Instant cleanupTime = created.plusSeconds(2);
        assertThat(store.findExpiredExecutionIds(cleanupTime, 100)).containsExactly(first.id());

        JdbcFlowStore.RequestExecutionRecord reused = transaction.execute(status ->
                store.lockExecution(
                        "app", "request-1", "b".repeat(64), cleanupTime, cleanupTime.plusSeconds(3600)));
        boolean deleted = transaction.execute(status -> store.deleteExpiredExecution(first.id(), cleanupTime));

        assertThat(reused.generation()).isEqualTo(2);
        assertThat(deleted).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_request_execution WHERE id=?",
                Integer.class,
                first.id())).isEqualTo(1);
    }

    @Test
    void cleanupDeletesAnExecutionThatIsStillExpiredUnderTheRowLock() {
        Instant created = Instant.parse("2026-08-31T08:00:00Z");
        JdbcFlowStore.RequestExecutionRecord record = transaction.execute(status ->
                store.lockExecution("app", "request-2", "c".repeat(64), created, created.plusSeconds(1)));

        boolean deleted = transaction.execute(status ->
                store.deleteExpiredExecution(record.id(), created.plusSeconds(2)));

        assertThat(deleted).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_request_execution WHERE id=?",
                Integer.class,
                record.id())).isZero();
    }

    private FlowInstance flow(Instant now) {
        return new FlowInstance(
                0, "flow-key", "TEST", "app", "provider", "flow", "", "",
                "a".repeat(64), "v1", "B***1", "release-1", "20", "b".repeat(64),
                1, FlowInstance.Status.ACTIVE, "PROCESSING", 0, Map.of(), 0,
                null, null, now.plusSeconds(60), now, now);
    }
}
