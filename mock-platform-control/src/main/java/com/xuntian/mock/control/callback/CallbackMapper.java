package com.xuntian.mock.control.callback;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface CallbackMapper {

    String TASK_COLUMNS = """
            id, task_id AS taskId, delivery_id AS deliveryId,
            flow_event_id AS flowEventId, flow_instance_id AS flowInstanceId,
            flow_generation AS flowGeneration, release_id AS releaseId,
            snapshot_checksum AS snapshotChecksum, callback_definition_id AS callbackDefinitionId,
            delivery_index AS deliveryIndex, provider_code AS providerCode, api_code AS apiCode,
            callback_url_encrypted AS callbackUrlEncrypted, http_method AS httpMethod,
            headers_json_encrypted AS headersJsonEncrypted, payload_encrypted AS payloadEncrypted,
            rendered_payload_hash AS renderedPayloadHash, encryption_key_id AS encryptionKeyId,
            callback_signature_policy_version_id AS callbackSignaturePolicyVersionId,
            callback_allowlist_policy_version_id AS callbackAllowlistPolicyVersionId,
            status, next_execute_at AS nextExecuteAt, send_attempt_count AS sendAttemptCount,
            max_retry AS maxRetry, CAST(retry_intervals_json AS CHAR) AS retryIntervalsJson,
            preparation_retry_count AS preparationRetryCount,
            max_preparation_retry AS maxPreparationRetry,
            manual_send_grant_count AS manualSendGrantCount,
            manual_preparation_grant_count AS manualPreparationGrantCount,
            lease_owner AS leaseOwner, lease_until AS leaseUntil, fencing_token AS fencingToken,
            last_http_status AS lastHttpStatus, last_error_masked AS lastErrorMasked,
            expire_at AS expireAt, created_at AS createdAt, updated_at AS updatedAt
            """;

    String ATTEMPT_COLUMNS = """
            id, task_id AS taskId, delivery_id AS deliveryId, attempt_no AS attemptNo,
            send_attempt_no AS sendAttemptNo, fencing_token AS fencingToken, status,
            started_at AS startedAt, completed_at AS completedAt, http_status AS httpStatus,
            result, delivery_certainty AS deliveryCertainty, error_masked AS errorMasked,
            duration_ms AS durationMs
            """;

    String FILTERS = """
            <if test='filter.status != null'>AND status = #{filter.status}</if>
            <if test='filter.providerCode != null'>AND provider_code = #{filter.providerCode}</if>
            <if test='filter.apiCode != null'>AND api_code = #{filter.apiCode}</if>
            <if test='filter.flowInstanceId != null'>AND flow_instance_id = #{filter.flowInstanceId}</if>
            <if test='filter.createdFrom != null'>AND created_at &gt;= #{filter.createdFrom}</if>
            <if test='filter.createdTo != null'>AND created_at &lt; #{filter.createdTo}</if>
            """;

    @Select("""
            <script>
            SELECT
            """ + TASK_COLUMNS + """
            FROM mock_callback_task
            WHERE 1 = 1
            """ + FILTERS + """
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<CallbackTaskRecord> selectPage(
            @Param("filter") CallbackTaskFilter filter,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM mock_callback_task WHERE 1 = 1
            """ + FILTERS + """
            </script>
            """)
    long count(@Param("filter") CallbackTaskFilter filter);

    @Select("""
            SELECT
            """ + TASK_COLUMNS + """
            FROM mock_callback_task WHERE task_id = #{taskId}
            """)
    CallbackTaskRecord selectTask(@Param("taskId") String taskId);

    @Select("""
            SELECT
            """ + TASK_COLUMNS + """
            FROM mock_callback_task WHERE task_id = #{taskId}
            FOR UPDATE
            """)
    CallbackTaskRecord lockTask(@Param("taskId") String taskId);

    @Select("""
            SELECT
            """ + TASK_COLUMNS + """
            FROM mock_callback_task
            WHERE (
                    status IN ('NEW', 'RETRYING') AND next_execute_at <= #{now}
                  )
               OR (
                    status = 'RUNNING' AND lease_until < #{now}
                  )
            ORDER BY next_execute_at, id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<CallbackTaskRecord> lockClaimable(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Update("""
            UPDATE mock_callback_task
            SET status = 'RUNNING', lease_owner = #{worker}, lease_until = #{leaseUntil},
                fencing_token = fencing_token + 1
            WHERE task_id = #{taskId} AND fencing_token = #{expectedFence}
              AND (
                    (status IN ('NEW', 'RETRYING') AND next_execute_at <= #{now})
                    OR (status = 'RUNNING' AND lease_until < #{now})
                  )
            """)
    int claim(
            @Param("taskId") String taskId,
            @Param("worker") String worker,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("expectedFence") long expectedFence,
            @Param("now") Instant now);

    @Select("""
            SELECT COALESCE(MAX(attempt_no), 0) + 1
            FROM mock_callback_attempt WHERE task_id = #{taskId}
            """)
    int nextAttemptNo(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO mock_callback_attempt (
                task_id, delivery_id, attempt_no, send_attempt_no, fencing_token,
                status, started_at
            ) VALUES (
                #{taskId}, #{deliveryId}, #{attemptNo}, NULL, #{fencingToken},
                'PREPARING', #{startedAt}
            )
            """)
    int insertPreparingAttempt(
            @Param("taskId") String taskId,
            @Param("deliveryId") String deliveryId,
            @Param("attemptNo") int attemptNo,
            @Param("fencingToken") long fencingToken,
            @Param("startedAt") Instant startedAt);

    @Select("""
            SELECT
            """ + ATTEMPT_COLUMNS + """
            FROM mock_callback_attempt
            WHERE task_id = #{taskId}
            ORDER BY attempt_no DESC LIMIT 1
            """)
    CallbackAttemptRecord selectLatestAttempt(@Param("taskId") String taskId);

    @Select("""
            SELECT
            """ + ATTEMPT_COLUMNS + """
            FROM mock_callback_attempt
            WHERE task_id = #{taskId}
            ORDER BY attempt_no
            """)
    List<CallbackAttemptRecord> selectAttempts(@Param("taskId") String taskId);

    @Update("""
            UPDATE mock_callback_attempt
            SET status = 'ABANDONED_PREPARATION', completed_at = #{now},
                result = 'LEASE_EXPIRED', delivery_certainty = 'NEVER_SENT',
                error_masked = 'Worker lease expired during preparation'
            WHERE task_id = #{taskId} AND fencing_token = #{fencingToken}
              AND status = 'PREPARING'
            """)
    int abandonPreparation(
            @Param("taskId") String taskId,
            @Param("fencingToken") long fencingToken,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_callback_attempt
            SET status = 'ABANDONED', completed_at = #{now}, result = 'LEASE_EXPIRED',
                delivery_certainty = 'UNKNOWN', error_masked = 'Worker lease expired after send started'
            WHERE task_id = #{taskId} AND fencing_token = #{fencingToken}
              AND status = 'STARTED'
            """)
    int abandonStarted(
            @Param("taskId") String taskId,
            @Param("fencingToken") long fencingToken,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_callback_task
            SET status = 'FAILED_UNCONFIRMED', lease_owner = NULL, lease_until = NULL,
                last_error_masked = 'Worker lease expired after send started'
            WHERE task_id = #{taskId} AND status = 'RUNNING'
              AND fencing_token = #{fencingToken}
            """)
    int exhaustExpiredStarted(
            @Param("taskId") String taskId,
            @Param("fencingToken") long fencingToken);

    @Update("""
            UPDATE mock_callback_task
            SET preparation_retry_count = preparation_retry_count + 1
            WHERE task_id = #{taskId} AND status = 'RUNNING'
              AND fencing_token = #{fencingToken}
            """)
    int incrementExpiredPreparation(
            @Param("taskId") String taskId,
            @Param("fencingToken") long fencingToken);

    @Update("""
            UPDATE mock_callback_task
            SET status = #{status}, lease_owner = NULL, lease_until = NULL,
                last_error_masked = #{error}
            WHERE task_id = #{taskId} AND status = 'RUNNING'
              AND fencing_token = #{fencingToken}
            """)
    int failExpiredRunning(
            @Param("taskId") String taskId,
            @Param("fencingToken") long fencingToken,
            @Param("status") String status,
            @Param("error") String error);

    @Update("""
            UPDATE mock_callback_task
            SET status = #{status}, lease_owner = NULL, lease_until = NULL,
                last_error_masked = #{error}
            WHERE task_id = #{taskId} AND status IN ('NEW', 'RETRYING')
            """)
    int failPending(
            @Param("taskId") String taskId,
            @Param("status") String status,
            @Param("error") String error);

    @Select("""
            SELECT
            """ + TASK_COLUMNS + """
            FROM mock_callback_task
            WHERE task_id = #{taskId} AND status = 'RUNNING'
              AND lease_owner = #{worker} AND fencing_token = #{fencingToken}
            FOR UPDATE
            """)
    CallbackTaskRecord lockOwned(
            @Param("taskId") String taskId,
            @Param("worker") String worker,
            @Param("fencingToken") long fencingToken);

    @Select("""
            SELECT COUNT(*) FROM mock_callback_task
            WHERE task_id = #{taskId} AND status = 'RUNNING'
              AND lease_owner = #{worker} AND fencing_token = #{fencingToken}
              AND lease_until > #{now}
            """)
    int ownsLiveLease(
            @Param("taskId") String taskId,
            @Param("worker") String worker,
            @Param("fencingToken") long fencingToken,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_callback_attempt
            SET status = 'CANCELLED', completed_at = #{now}, result = #{result},
                delivery_certainty = 'NEVER_SENT', error_masked = #{error}
            WHERE task_id = #{taskId} AND fencing_token = #{fencingToken}
              AND status = 'PREPARING'
            """)
    int cancelPreparingAttempt(
            @Param("taskId") String taskId,
            @Param("fencingToken") long fencingToken,
            @Param("now") Instant now,
            @Param("result") String result,
            @Param("error") String error);

    @Update("""
            UPDATE mock_callback_attempt
            SET status = 'PREPARATION_FAILED', completed_at = #{now}, result = 'PREPARATION_FAILED',
                delivery_certainty = 'NEVER_SENT', error_masked = #{error}
            WHERE task_id = #{taskId} AND fencing_token = #{fencingToken}
              AND status = 'PREPARING'
            """)
    int failPreparingAttempt(
            @Param("taskId") String taskId,
            @Param("fencingToken") long fencingToken,
            @Param("now") Instant now,
            @Param("error") String error);

    @Update("""
            UPDATE mock_callback_attempt
            SET status = 'STARTED', send_attempt_no = #{sendAttemptNo}
            WHERE task_id = #{taskId} AND fencing_token = #{fencingToken}
              AND status = 'PREPARING'
            """)
    int startAttempt(
            @Param("taskId") String taskId,
            @Param("fencingToken") long fencingToken,
            @Param("sendAttemptNo") int sendAttemptNo);

    @Update("""
            UPDATE mock_callback_task
            SET send_attempt_count = send_attempt_count + 1, lease_until = #{leaseUntil}
            WHERE task_id = #{taskId} AND status = 'RUNNING'
              AND lease_owner = #{worker} AND fencing_token = #{fencingToken}
            """)
    int markSendStarted(
            @Param("taskId") String taskId,
            @Param("worker") String worker,
            @Param("fencingToken") long fencingToken,
            @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            UPDATE mock_callback_attempt
            SET status = #{status}, completed_at = #{completedAt}, http_status = #{httpStatus},
                result = #{result}, delivery_certainty = #{certainty},
                error_masked = #{error}, duration_ms = #{durationMs}
            WHERE task_id = #{taskId} AND fencing_token = #{fencingToken}
              AND status = 'STARTED'
            """)
    int completeStartedAttempt(
            @Param("taskId") String taskId,
            @Param("fencingToken") long fencingToken,
            @Param("status") String status,
            @Param("completedAt") Instant completedAt,
            @Param("httpStatus") Integer httpStatus,
            @Param("result") String result,
            @Param("certainty") String certainty,
            @Param("error") String error,
            @Param("durationMs") long durationMs);

    @Update("""
            UPDATE mock_callback_task
            SET status = #{status}, next_execute_at = COALESCE(#{nextExecuteAt}, next_execute_at),
                preparation_retry_count = preparation_retry_count + #{preparationIncrement},
                lease_owner = NULL, lease_until = NULL,
                last_http_status = #{httpStatus}, last_error_masked = #{error}
            WHERE task_id = #{taskId} AND status = 'RUNNING'
              AND lease_owner = #{worker} AND fencing_token = #{fencingToken}
            """)
    int finishOwnedTask(
            @Param("taskId") String taskId,
            @Param("worker") String worker,
            @Param("fencingToken") long fencingToken,
            @Param("status") String status,
            @Param("nextExecuteAt") Instant nextExecuteAt,
            @Param("preparationIncrement") int preparationIncrement,
            @Param("httpStatus") Integer httpStatus,
            @Param("error") String error);

    @Update("""
            UPDATE mock_callback_task
            SET status = 'RETRYING', next_execute_at = #{nextExecuteAt},
                manual_send_grant_count = manual_send_grant_count + #{sendGrant},
                manual_preparation_grant_count = manual_preparation_grant_count + #{preparationGrant},
                lease_owner = NULL, lease_until = NULL
            WHERE task_id = #{taskId} AND status = #{expectedStatus}
            """)
    int manualRetry(
            @Param("taskId") String taskId,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextExecuteAt") Instant nextExecuteAt,
            @Param("sendGrant") int sendGrant,
            @Param("preparationGrant") int preparationGrant);

    @Update("""
            UPDATE mock_callback_task
            SET status = 'CANCELLED', lease_owner = NULL, lease_until = NULL,
                last_error_masked = #{reason}
            WHERE task_id = #{taskId} AND status IN ('NEW', 'RETRYING')
            """)
    int cancelPending(@Param("taskId") String taskId, @Param("reason") String reason);

    @Select("""
            SELECT id, generation, status FROM mock_flow_instance WHERE id = #{flowInstanceId}
            """)
    CallbackFlowState selectFlowState(@Param("flowInstanceId") long flowInstanceId);

    @Select("""
            SELECT id, request_id AS requestId, operation_type AS operationType,
                   resource_type AS resourceType, resource_id AS resourceId,
                   request_checksum AS requestChecksum, status,
                   CAST(result_json AS CHAR) AS resultJson, `operator`, created_at AS createdAt,
                   completed_at AS completedAt
            FROM mock_admin_operation WHERE request_id = #{requestId}
            """)
    CallbackAdminOperationRecord selectAdminOperation(@Param("requestId") String requestId);

    @Select("""
            SELECT id, request_id AS requestId, operation_type AS operationType,
                   resource_type AS resourceType, resource_id AS resourceId,
                   request_checksum AS requestChecksum, status,
                   CAST(result_json AS CHAR) AS resultJson, `operator`, created_at AS createdAt,
                   completed_at AS completedAt
            FROM mock_admin_operation WHERE request_id = #{requestId}
            FOR UPDATE
            """)
    CallbackAdminOperationRecord lockAdminOperation(@Param("requestId") String requestId);

    @Insert("""
            INSERT INTO mock_admin_operation (
                request_id, operation_type, resource_type, resource_id,
                request_checksum, status, `operator`
            ) VALUES (
                #{requestId}, #{operationType}, 'CALLBACK_TASK', #{taskId},
                #{requestChecksum}, 'IN_TRANSACTION', #{operator}
            )
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertAdminOperation(
            @Param("requestId") String requestId,
            @Param("operationType") String operationType,
            @Param("taskId") String taskId,
            @Param("requestChecksum") String requestChecksum,
            @Param("operator") String operator);

    @Update("""
            UPDATE mock_admin_operation
            SET status = 'COMPLETED', result_json = CAST(#{resultJson} AS JSON), completed_at = #{completedAt}
            WHERE request_id = #{requestId} AND status = 'IN_TRANSACTION'
            """)
    int completeAdminOperation(
            @Param("requestId") String requestId,
            @Param("resultJson") String resultJson,
            @Param("completedAt") Instant completedAt);
}
