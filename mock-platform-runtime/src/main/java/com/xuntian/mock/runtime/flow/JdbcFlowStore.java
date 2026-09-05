package com.xuntian.mock.runtime.flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.engine.RuntimeFault;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
@Profile("!test")
public final class JdbcFlowStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcFlowStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy();
    }

    public RequestExecutionRecord lockExecution(
            String app,
            String mockRequestId,
            String fingerprint,
            Instant now,
            Instant expireAt) {
        jdbc.update("""
                INSERT INTO mock_request_execution (
                    app_code, mock_request_id, execution_generation, request_fingerprint,
                    status, expire_at, created_at
                ) VALUES (?, ?, 1, ?, 'IN_TRANSACTION', ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, app, mockRequestId, fingerprint, timestamp(expireAt), timestamp(now));
        RequestExecutionRecord record = jdbc.queryForObject("""
                SELECT id, app_code, mock_request_id, execution_generation, request_fingerprint,
                       status, release_id, activation_version, scenario_version_id,
                       flow_instance_id, flow_generation, transition_result_json,
                       response_status, response_headers_encrypted, response_body_encrypted,
                       fault_type, fault_duration_ms, side_effect_policy,
                       encryption_key_id, expire_at
                  FROM mock_request_execution
                 WHERE app_code = ? AND mock_request_id = ?
                 FOR UPDATE
                """, executionMapper(), app, mockRequestId);
        if (record == null) throw new IllegalStateException("Request Execution lock returned no row");
        if (!record.expireAt().isAfter(now)) {
            jdbc.update("""
                    UPDATE mock_request_execution
                       SET execution_generation = execution_generation + 1,
                           request_fingerprint = ?, status = 'IN_TRANSACTION',
                           release_id = NULL, activation_version = NULL,
                           scenario_version_id = NULL, flow_instance_id = NULL,
                           flow_generation = NULL, transition_result_json = NULL,
                           response_status = NULL, response_headers_encrypted = NULL,
                           response_body_encrypted = NULL, fault_type = NULL,
                           fault_duration_ms = NULL, side_effect_policy = NULL,
                           encryption_key_id = NULL, expire_at = ?, created_at = ?, completed_at = NULL
                     WHERE id = ?
                    """, fingerprint, timestamp(expireAt), timestamp(now), record.id());
            return record.reset(fingerprint, expireAt);
        }
        if (!record.fingerprint().equals(fingerprint)) {
            throw new PlatformException(
                    ErrorCode.MOCK_IDEMPOTENCY_CONFLICT,
                    "X-Mock-Request-Id was already used with a different request fingerprint");
        }
        if (!"IN_TRANSACTION".equals(record.status()) && !"COMPLETED".equals(record.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Request Execution status is invalid");
        }
        return record;
    }

    public void completeExecution(
            RequestExecutionRecord record,
            String releaseId,
            long activationVersion,
            long scenarioVersionId,
            Long flowInstanceId,
            Integer flowGeneration,
            String transitionResultJson,
            int responseStatus,
            RuntimeCryptography.ProtectedValue headers,
            RuntimeCryptography.ProtectedValue body,
            RuntimeFault fault,
            Instant completedAt) {
        if (!headers.keyId().equals(body.keyId())) {
            throw new IllegalArgumentException("Execution encrypted values must use one key version");
        }
        int updated = jdbc.update("""
                UPDATE mock_request_execution
                   SET status = 'COMPLETED', release_id = ?, activation_version = ?,
                       scenario_version_id = ?, flow_instance_id = ?, flow_generation = ?,
                       transition_result_json = ?, response_status = ?,
                       response_headers_encrypted = ?, response_body_encrypted = ?,
                       fault_type = ?, fault_duration_ms = ?, side_effect_policy = ?,
                       encryption_key_id = ?,
                       completed_at = ?
                 WHERE id = ? AND execution_generation = ? AND status = 'IN_TRANSACTION'
                """, statement -> {
            statement.setString(1, releaseId);
            statement.setLong(2, activationVersion);
            statement.setLong(3, scenarioVersionId);
            nullableLong(statement, 4, flowInstanceId);
            nullableInt(statement, 5, flowGeneration);
            statement.setString(6, transitionResultJson);
            statement.setInt(7, responseStatus);
            statement.setBytes(8, headers.ciphertext());
            statement.setBytes(9, body.ciphertext());
            statement.setString(10, fault.type().name());
            statement.setLong(11, fault.durationMs());
            statement.setString(12, fault.sideEffectPolicy().name());
            statement.setString(13, headers.keyId());
            statement.setTimestamp(14, timestamp(completedAt));
            statement.setLong(15, record.id());
            statement.setInt(16, record.generation());
        });
        if (updated != 1) {
            throw new PlatformException(ErrorCode.MOCK_FLOW_CONFLICT, "Request Execution fencing check failed");
        }
    }

    public List<Long> findExpiredExecutionIds(Instant now, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be from 1 to 1000");
        return jdbc.queryForList("""
                SELECT id
                  FROM mock_request_execution
                 WHERE expire_at <= ?
                 ORDER BY expire_at, id
                 LIMIT ?
                """, Long.class, timestamp(now), limit);
    }

    public boolean deleteExpiredExecution(long id, Instant now) {
        List<ExecutionExpiry> rows = jdbc.query("""
                SELECT execution_generation, expire_at
                  FROM mock_request_execution
                 WHERE id = ?
                 FOR UPDATE
                """, (result, row) -> new ExecutionExpiry(
                result.getInt("execution_generation"),
                result.getTimestamp("expire_at").toInstant()), id);
        if (rows.isEmpty() || rows.get(0).expireAt().isAfter(now)) return false;
        ExecutionExpiry locked = rows.get(0);
        return jdbc.update("""
                DELETE FROM mock_request_execution
                 WHERE id = ? AND execution_generation = ? AND expire_at <= ?
                """, id, locked.generation(), timestamp(now)) == 1;
    }

    public FlowInstance findFlowForUpdate(
            RuntimeFlowScope scope,
            List<FlowTransitionService.FlowKey> candidates) {
        if (candidates.isEmpty()) return null;
        StringBuilder sql = new StringBuilder("""
                SELECT id, flow_key, environment, app_code, provider_code, flow_code,
                       tenant_code, test_account, business_no_hmac, hmac_key_version,
                       business_no_masked, release_id, flow_definition_version_id,
                       flow_definition_checksum, generation, status, current_state,
                       query_count, variables_json, version, pending_transition_id,
                       next_transition_at, expire_at, created_at, updated_at
                  FROM mock_flow_instance
                 WHERE environment = ? AND app_code = ? AND provider_code = ? AND flow_code = ?
                   AND tenant_code = ? AND test_account = ? AND (
                """);
        List<Object> arguments = new ArrayList<>(List.of(
                scope.environment(), scope.app(), scope.provider(), scope.flowCode(),
                scope.tenant(), scope.testAccount()));
        for (int index = 0; index < candidates.size(); index++) {
            if (index > 0) sql.append(" OR ");
            sql.append("(hmac_key_version = ? AND business_no_hmac = ?)");
            arguments.add(candidates.get(index).hmacKeyVersion());
            arguments.add(candidates.get(index).businessNoHmac());
        }
        sql.append(") ORDER BY id LIMIT 1 FOR UPDATE");
        List<FlowInstance> rows = jdbc.query(sql.toString(), flowMapper(), arguments.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public InsertOrLockResult insertOrLock(FlowInstance source) {
        jdbc.queryForObject("SELECT LAST_INSERT_ID(0)", Long.class);
        jdbc.update("""
                INSERT INTO mock_flow_instance (
                    flow_key, environment, app_code, provider_code, flow_code, tenant_code,
                    test_account, business_no_hmac, hmac_key_version, business_no_masked,
                    release_id, flow_definition_version_id, flow_definition_checksum,
                    generation, status, current_state, query_count, variables_json, version,
                    pending_transition_id, next_transition_at, expire_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, source.flowKey(), source.environment(), source.appCode(), source.providerCode(),
                source.flowCode(), source.tenantCode(), source.testAccount(), source.businessNoHmac(),
                source.hmacKeyVersion(), source.businessNoMasked(), source.releaseId(),
                Long.parseLong(source.flowDefinitionVersionId()), source.flowDefinitionChecksum(),
                source.generation(), source.status().name(), source.currentState(), source.queryCount(),
                json(source.variables()), source.version(), source.pendingTransitionId(),
                timestamp(source.nextTransitionAt()), timestamp(source.expireAt()),
                timestamp(source.createdAt()), timestamp(source.updatedAt()));
        Long insertedId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        FlowInstance persisted = jdbc.queryForObject("""
                SELECT id, flow_key, environment, app_code, provider_code, flow_code,
                       tenant_code, test_account, business_no_hmac, hmac_key_version,
                       business_no_masked, release_id, flow_definition_version_id,
                       flow_definition_checksum, generation, status, current_state,
                       query_count, variables_json, version, pending_transition_id,
                       next_transition_at, expire_at, created_at, updated_at
                  FROM mock_flow_instance
                 WHERE flow_key = ? OR (
                       environment = ? AND app_code = ? AND provider_code = ? AND flow_code = ?
                       AND tenant_code = ? AND test_account = ?
                       AND hmac_key_version = ? AND business_no_hmac = ?)
                 ORDER BY id LIMIT 1 FOR UPDATE
                """, flowMapper(), source.flowKey(), source.environment(), source.appCode(),
                source.providerCode(), source.flowCode(), source.tenantCode(), source.testAccount(),
                source.hmacKeyVersion(), source.businessNoHmac());
        return new InsertOrLockResult(persisted, insertedId != null && insertedId > 0);
    }

    public void cancelPendingCallbacksOrFailBusy(long flowInstanceId, int generation, Instant now) {
        List<String> statuses = jdbc.queryForList("""
                SELECT status
                  FROM mock_callback_task
                 WHERE flow_instance_id = ? AND flow_generation = ?
                   AND status IN ('NEW', 'RETRYING', 'RUNNING')
                 ORDER BY id
                 FOR UPDATE
                """, String.class, flowInstanceId, generation);
        if (statuses.stream().anyMatch("RUNNING"::equals)) {
            throw new PlatformException(
                    ErrorCode.MOCK_FLOW_OPERATION_BUSY,
                    "Flow has a running Callback task");
        }
        jdbc.update("""
                UPDATE mock_callback_task
                   SET status = 'CANCELLED', lease_owner = NULL, lease_until = NULL,
                       last_error_masked = 'Flow generation was reactivated', updated_at = ?
                 WHERE flow_instance_id = ? AND flow_generation = ?
                   AND status IN ('NEW', 'RETRYING')
                """, timestamp(now), flowInstanceId, generation);
    }

    public void updateFlow(FlowInstance before, FlowInstance after) {
        int updated = jdbc.update("""
                UPDATE mock_flow_instance
                   SET flow_key = ?, business_no_hmac = ?, hmac_key_version = ?,
                       business_no_masked = ?, release_id = ?, flow_definition_version_id = ?,
                       flow_definition_checksum = ?, generation = ?, status = ?, current_state = ?,
                       query_count = ?, variables_json = ?, version = ?, pending_transition_id = ?,
                       next_transition_at = ?, expire_at = ?, updated_at = ?
                 WHERE id = ? AND version = ?
                """, after.flowKey(), after.businessNoHmac(), after.hmacKeyVersion(),
                after.businessNoMasked(), after.releaseId(), Long.parseLong(after.flowDefinitionVersionId()),
                after.flowDefinitionChecksum(), after.generation(), after.status().name(),
                after.currentState(), after.queryCount(), json(after.variables()), after.version(),
                after.pendingTransitionId(), timestamp(after.nextTransitionAt()), timestamp(after.expireAt()),
                timestamp(after.updatedAt()), before.id(), before.version());
        if (updated != 1) {
            throw new PlatformException(ErrorCode.MOCK_FLOW_CONFLICT, "Flow version changed concurrently");
        }
    }

    public FlowEvent insertEvent(FlowEvent event) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mock_flow_event (
                        event_id, flow_instance_id, flow_generation, source_type, mock_request_id,
                        request_execution_generation, internal_execution_id, source_api_code,
                        transition_id, from_state, to_state, event_type, query_count, `operator`, event_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, event.eventId());
            statement.setLong(2, event.flowInstanceId());
            statement.setInt(3, event.flowGeneration());
            statement.setString(4, event.sourceType().name());
            nullableString(statement, 5, event.mockRequestId());
            nullableInt(statement, 6, event.requestExecutionGeneration());
            nullableString(statement, 7, event.internalExecutionId());
            nullableString(statement, 8, event.sourceApiCode());
            nullableString(statement, 9, event.transitionId());
            nullableString(statement, 10, event.fromState());
            nullableString(statement, 11, event.toState());
            statement.setString(12, event.eventType());
            statement.setLong(13, event.queryCount());
            nullableString(statement, 14, event.operator());
            statement.setTimestamp(15, timestamp(event.eventAt()));
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("Flow Event insert returned no id");
        return event.persisted(id.longValue());
    }

    public void insertCallback(CallbackTaskInsert task) {
        jdbc.update("""
                INSERT INTO mock_callback_task (
                    task_id, delivery_id, flow_event_id, flow_instance_id, flow_generation,
                    release_id, snapshot_checksum, callback_definition_id, delivery_index,
                    provider_code, api_code, callback_url_encrypted, http_method,
                    headers_json_encrypted, payload_encrypted, rendered_payload_hash,
                    encryption_key_id, callback_signature_policy_version_id,
                    callback_allowlist_policy_version_id, status, next_execute_at,
                    send_attempt_count, max_retry, retry_intervals_json,
                    preparation_retry_count, max_preparation_retry,
                    manual_send_grant_count, manual_preparation_grant_count, fencing_token,
                    expire_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          'NEW', ?, 0, ?, ?, 0, 3, 0, 0, 0, ?, ?, ?)
                """, task.taskId(), task.deliveryId(), task.flowEventId(), task.flowInstanceId(),
                task.flowGeneration(), task.releaseId(), task.snapshotChecksum(),
                task.callbackDefinitionId(), task.deliveryIndex(), task.provider(), task.api(),
                task.urlEncrypted(), task.method(), task.headersEncrypted(), task.payloadEncrypted(),
                task.payloadHash(), task.encryptionKeyId(), task.signaturePolicyVersionId(),
                task.allowlistPolicyVersionId(), timestamp(task.nextExecuteAt()), task.maxRetry(),
                json(task.retryIntervalsMs()),
                timestamp(task.expireAt()), timestamp(task.createdAt()), timestamp(task.createdAt()));
    }

    private RowMapper<RequestExecutionRecord> executionMapper() {
        return (result, row) -> new RequestExecutionRecord(
                result.getLong("id"), result.getString("app_code"), result.getString("mock_request_id"),
                result.getInt("execution_generation"), result.getString("request_fingerprint"),
                result.getString("status"), result.getString("release_id"),
                nullableLong(result, "activation_version"), nullableLong(result, "scenario_version_id"),
                nullableLong(result, "flow_instance_id"), nullableInt(result, "flow_generation"),
                result.getString("transition_result_json"), nullableInt(result, "response_status"),
                result.getBytes("response_headers_encrypted"), result.getBytes("response_body_encrypted"),
                result.getString("fault_type"), nullableLong(result, "fault_duration_ms"),
                result.getString("side_effect_policy"),
                result.getString("encryption_key_id"), result.getTimestamp("expire_at").toInstant());
    }

    private RowMapper<FlowInstance> flowMapper() {
        return (result, row) -> new FlowInstance(
                result.getLong("id"), result.getString("flow_key"), result.getString("environment"),
                result.getString("app_code"), result.getString("provider_code"), result.getString("flow_code"),
                result.getString("tenant_code"), result.getString("test_account"),
                result.getString("business_no_hmac"), result.getString("hmac_key_version"),
                result.getString("business_no_masked"), result.getString("release_id"),
                result.getString("flow_definition_version_id"), result.getString("flow_definition_checksum"),
                result.getInt("generation"), FlowInstance.Status.valueOf(result.getString("status")),
                result.getString("current_state"), result.getLong("query_count"),
                map(result.getString("variables_json")), result.getLong("version"),
                result.getString("pending_transition_id"), instant(result.getTimestamp("next_transition_at")),
                result.getTimestamp("expire_at").toInstant(), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Runtime state cannot be serialized", failure);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return mapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Flow variables are invalid", failure);
        }
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Long nullableLong(java.sql.ResultSet result, String field) throws java.sql.SQLException {
        long value = result.getLong(field); return result.wasNull() ? null : value;
    }
    private static Integer nullableInt(java.sql.ResultSet result, String field) throws java.sql.SQLException {
        int value = result.getInt(field); return result.wasNull() ? null : value;
    }
    private static void nullableString(PreparedStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }
    private static void nullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }
    private static void nullableInt(PreparedStatement statement, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
    }

    public record RuntimeFlowScope(
            String environment, String app, String provider, String flowCode,
            String tenant, String testAccount) { }

    public record InsertOrLockResult(FlowInstance instance, boolean inserted) { }

    private record ExecutionExpiry(int generation, Instant expireAt) { }


    public record RequestExecutionRecord(
            long id, String app, String mockRequestId, int generation, String fingerprint,
            String status, String releaseId, Long activationVersion, Long scenarioVersionId,
            Long flowInstanceId, Integer flowGeneration, String transitionResultJson,
            Integer responseStatus, byte[] responseHeadersEncrypted, byte[] responseBodyEncrypted,
            String faultType, Long faultDurationMs, String sideEffectPolicy,
            String encryptionKeyId, Instant expireAt) {
        public RequestExecutionRecord {
            responseHeadersEncrypted = clone(responseHeadersEncrypted);
            responseBodyEncrypted = clone(responseBodyEncrypted);
        }
        public boolean completed() { return "COMPLETED".equals(status); }
        RequestExecutionRecord reset(String newFingerprint, Instant newExpireAt) {
            return new RequestExecutionRecord(
                    id, app, mockRequestId, generation + 1, newFingerprint, "IN_TRANSACTION",
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, newExpireAt);
        }
        private static byte[] clone(byte[] value) { return value == null ? null : value.clone(); }
    }

    public record CallbackTaskInsert(
            String taskId, String deliveryId, long flowEventId, long flowInstanceId,
            int flowGeneration, String releaseId, String snapshotChecksum,
            String callbackDefinitionId, int deliveryIndex, String provider, String api,
            byte[] urlEncrypted, String method, byte[] headersEncrypted, byte[] payloadEncrypted,
            String payloadHash, String encryptionKeyId, long signaturePolicyVersionId,
            long allowlistPolicyVersionId, Instant nextExecuteAt, int maxRetry,
            List<Long> retryIntervalsMs, Instant expireAt, Instant createdAt) { }
}
