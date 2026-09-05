package com.xuntian.mock.control.flow;

import com.xuntian.mock.control.callback.CallbackAdminOperationRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface FlowInstanceMapper {

    String FLOW_COLUMNS = """
            id, flow_key AS flowKey, environment, app_code AS appCode,
            provider_code AS providerCode, flow_code AS flowCode,
            tenant_code AS tenantCode, test_account AS testAccount,
            business_no_hmac AS businessNoHmac, hmac_key_version AS hmacKeyVersion,
            business_no_masked AS businessNoMasked, release_id AS releaseId,
            flow_definition_version_id AS flowDefinitionVersionId,
            flow_definition_checksum AS flowDefinitionChecksum, generation, status,
            current_state AS currentState, query_count AS queryCount,
            CAST(variables_json AS CHAR) AS variablesJson, version,
            pending_transition_id AS pendingTransitionId,
            next_transition_at AS nextTransitionAt, expire_at AS expireAt,
            created_at AS createdAt, updated_at AS updatedAt
            """;

    String EVENT_COLUMNS = """
            id, event_id AS eventId, flow_instance_id AS flowInstanceId,
            flow_generation AS flowGeneration, source_type AS sourceType,
            mock_request_id AS mockRequestId,
            request_execution_generation AS requestExecutionGeneration,
            internal_execution_id AS internalExecutionId, source_api_code AS sourceApiCode,
            transition_id AS transitionId, from_state AS fromState, to_state AS toState,
            event_type AS eventType, query_count AS queryCount, `operator`, event_at AS eventAt
            """;

    String FILTERS = """
            <if test='filter.environment != null'>AND environment = #{filter.environment}</if>
            <if test='filter.appCode != null'>AND app_code = #{filter.appCode}</if>
            <if test='filter.providerCode != null'>AND provider_code = #{filter.providerCode}</if>
            <if test='filter.flowCode != null'>AND flow_code = #{filter.flowCode}</if>
            <if test='filter.status != null'>AND status = #{filter.status}</if>
            """;

    @Select("""
            <script>SELECT
            """ + FLOW_COLUMNS + """
            FROM mock_flow_instance WHERE 1 = 1
            """ + FILTERS + """
            ORDER BY updated_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}</script>
            """)
    List<FlowInstanceRecord> selectPage(
            @Param("filter") FlowInstanceFilter filter,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select("""
            <script>SELECT COUNT(*) FROM mock_flow_instance WHERE 1 = 1
            """ + FILTERS + """
            </script>
            """)
    long count(@Param("filter") FlowInstanceFilter filter);

    @Select("SELECT " + FLOW_COLUMNS + " FROM mock_flow_instance WHERE flow_key = #{flowKey}")
    FlowInstanceRecord selectByKey(@Param("flowKey") String flowKey);

    @Select("SELECT " + FLOW_COLUMNS + " FROM mock_flow_instance WHERE flow_key = #{flowKey} FOR UPDATE")
    FlowInstanceRecord lockByKey(@Param("flowKey") String flowKey);

    @Select("""
            SELECT
            """ + EVENT_COLUMNS + """
            FROM mock_flow_event WHERE flow_instance_id = #{flowId}
            ORDER BY event_at, id
            """)
    List<FlowEventRecord> selectEvents(@Param("flowId") long flowId);

    @Select("""
            SELECT
            """ + FLOW_COLUMNS + """
            FROM mock_flow_instance
            WHERE status = 'ACTIVE' AND next_transition_at IS NOT NULL
              AND next_transition_at <= #{now}
            ORDER BY next_transition_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    FlowInstanceRecord lockNextDue(@Param("now") Instant now);

    @Select("""
            SELECT id, task_id AS taskId, status FROM mock_callback_task
            WHERE flow_instance_id = #{flowId} AND flow_generation = #{generation}
              AND status IN ('NEW', 'RETRYING', 'RUNNING')
            ORDER BY id FOR UPDATE
            """)
    List<FlowCallbackState> lockPendingCallbacks(
            @Param("flowId") long flowId,
            @Param("generation") int generation);

    @Update("""
            UPDATE mock_callback_task SET status = 'CANCELLED', lease_owner = NULL,
                lease_until = NULL, last_error_masked = #{reason}, updated_at = #{now}
            WHERE flow_instance_id = #{flowId} AND flow_generation = #{generation}
              AND status IN ('NEW', 'RETRYING')
            """)
    int cancelPendingCallbacks(
            @Param("flowId") long flowId,
            @Param("generation") int generation,
            @Param("reason") String reason,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_flow_instance
            SET current_state = #{state}, variables_json = CAST(#{variablesJson} AS JSON),
                version = version + 1, pending_transition_id = #{pendingId},
                next_transition_at = #{pendingAt}, updated_at = #{now}
            WHERE id = #{id} AND version = #{expectedVersion} AND generation = #{generation}
              AND status = 'ACTIVE'
            """)
    int advance(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("generation") int generation,
            @Param("state") String state,
            @Param("variablesJson") String variablesJson,
            @Param("pendingId") String pendingId,
            @Param("pendingAt") Instant pendingAt,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_flow_instance
            SET release_id = #{releaseId}, flow_definition_version_id = #{definitionVersionId},
                flow_definition_checksum = #{definitionChecksum}, generation = generation + 1,
                status = 'ACTIVE', current_state = #{state}, query_count = 0,
                variables_json = CAST(#{variablesJson} AS JSON), version = version + 1,
                pending_transition_id = #{pendingId}, next_transition_at = #{pendingAt},
                expire_at = #{expireAt}, updated_at = #{now}
            WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int reset(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("releaseId") String releaseId,
            @Param("definitionVersionId") long definitionVersionId,
            @Param("definitionChecksum") String definitionChecksum,
            @Param("state") String state,
            @Param("variablesJson") String variablesJson,
            @Param("pendingId") String pendingId,
            @Param("pendingAt") Instant pendingAt,
            @Param("expireAt") Instant expireAt,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_flow_instance
            SET generation = generation + 1, status = 'DELETED', version = version + 1,
                pending_transition_id = NULL, next_transition_at = NULL, updated_at = #{now}
            WHERE id = #{id} AND version = #{expectedVersion} AND status <> 'DELETED'
            """)
    int delete(@Param("id") long id, @Param("expectedVersion") long expectedVersion, @Param("now") Instant now);

    @Update("""
            UPDATE mock_flow_instance SET status = 'EXPIRED', version = version + 1,
                pending_transition_id = NULL, next_transition_at = NULL, updated_at = #{now}
            WHERE id = #{id} AND version = #{expectedVersion} AND status = 'ACTIVE'
            """)
    int expire(@Param("id") long id, @Param("expectedVersion") long expectedVersion, @Param("now") Instant now);

    @Insert("""
            INSERT INTO mock_flow_event (
                event_id, flow_instance_id, flow_generation, source_type,
                internal_execution_id, transition_id, from_state, to_state,
                event_type, query_count, `operator`, event_at
            ) VALUES (
                #{eventId}, #{flowId}, #{generation}, #{sourceType},
                #{internalId}, #{transitionId}, #{fromState}, #{toState},
                #{eventType}, #{queryCount}, #{operator}, #{now}
            )
            """)
    int insertEvent(
            @Param("eventId") String eventId,
            @Param("flowId") long flowId,
            @Param("generation") int generation,
            @Param("sourceType") String sourceType,
            @Param("internalId") String internalId,
            @Param("transitionId") String transitionId,
            @Param("fromState") String fromState,
            @Param("toState") String toState,
            @Param("eventType") String eventType,
            @Param("queryCount") long queryCount,
            @Param("operator") String operator,
            @Param("now") Instant now);

    @Select("SELECT id FROM mock_flow_event WHERE event_id = #{eventId}")
    Long selectEventId(@Param("eventId") String eventId);

    @Insert("""
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
            ) VALUES (
                #{taskId}, #{deliveryId}, #{eventId}, #{flowId}, #{generation},
                #{releaseId}, #{snapshotChecksum}, #{callbackId}, #{deliveryIndex},
                #{provider}, #{api}, #{urlEncrypted}, #{method},
                #{headersEncrypted}, #{payloadEncrypted}, #{payloadHash},
                #{keyId}, #{signaturePolicyId}, #{allowlistPolicyId}, 'NEW', #{nextAt},
                0, #{maxRetry}, CAST(#{retryIntervalsJson} AS JSON),
                0, 3, 0, 0, 0, #{expireAt}, #{now}, #{now}
            )
            """)
    int insertCallback(
            @Param("taskId") String taskId,
            @Param("deliveryId") String deliveryId,
            @Param("eventId") long eventId,
            @Param("flowId") long flowId,
            @Param("generation") int generation,
            @Param("releaseId") String releaseId,
            @Param("snapshotChecksum") String snapshotChecksum,
            @Param("callbackId") String callbackId,
            @Param("deliveryIndex") int deliveryIndex,
            @Param("provider") String provider,
            @Param("api") String api,
            @Param("urlEncrypted") byte[] urlEncrypted,
            @Param("method") String method,
            @Param("headersEncrypted") byte[] headersEncrypted,
            @Param("payloadEncrypted") byte[] payloadEncrypted,
            @Param("payloadHash") String payloadHash,
            @Param("keyId") String keyId,
            @Param("signaturePolicyId") long signaturePolicyId,
            @Param("allowlistPolicyId") long allowlistPolicyId,
            @Param("nextAt") Instant nextAt,
            @Param("maxRetry") int maxRetry,
            @Param("retryIntervalsJson") String retryIntervalsJson,
            @Param("expireAt") Instant expireAt,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO mock_admin_operation (
                request_id, operation_type, resource_type, resource_id,
                request_checksum, status, `operator`
            ) VALUES (#{requestId}, #{operationType}, 'FLOW_INSTANCE', #{flowKey},
                #{checksum}, 'IN_TRANSACTION', #{operator})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertAdminOperation(
            @Param("requestId") String requestId,
            @Param("operationType") String operationType,
            @Param("flowKey") String flowKey,
            @Param("checksum") String checksum,
            @Param("operator") String operator);

    @Select("""
            SELECT id, request_id AS requestId, operation_type AS operationType,
                   resource_type AS resourceType, resource_id AS resourceId,
                   request_checksum AS requestChecksum, status,
                   CAST(result_json AS CHAR) AS resultJson, `operator`,
                   created_at AS createdAt, completed_at AS completedAt
            FROM mock_admin_operation WHERE request_id = #{requestId} FOR UPDATE
            """)
    CallbackAdminOperationRecord lockAdminOperation(@Param("requestId") String requestId);

    @Update("""
            UPDATE mock_admin_operation SET status = 'COMPLETED',
                result_json = CAST(#{resultJson} AS JSON), completed_at = #{now}
            WHERE request_id = #{requestId} AND status = 'IN_TRANSACTION'
            """)
    int completeAdminOperation(
            @Param("requestId") String requestId,
            @Param("resultJson") String resultJson,
            @Param("now") Instant now);
}
