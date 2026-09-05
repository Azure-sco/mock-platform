package com.xuntian.mock.control.release;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ReleaseMapper {

    String RELEASE_COLUMNS = """
            id, release_code AS releaseCode, environment, app_code AS appCode, status,
            CAST(snapshot_json AS CHAR) AS snapshotJson, snapshot_bytes AS snapshotBytes,
            checksum, schema_version AS schemaVersion, signature,
            signature_key_id AS signatureKeyId, signature_algorithm AS signatureAlgorithm,
            release_note AS releaseNote, failure_reason AS failureReason,
            created_by AS createdBy, created_at AS createdAt,
            published_by AS publishedBy, published_at AS publishedAt
            """;

    String ACTIVE_COLUMNS = """
            environment, app_code AS appCode, release_id AS releaseId,
            activation_version AS activationVersion, state, updated_at AS updatedAt
            """;

    String ACTIVATION_COLUMNS = """
            id, environment, app_code AS appCode, from_release_id AS fromReleaseId,
            to_release_id AS toReleaseId, from_activation_version AS fromActivationVersion,
            to_activation_version AS toActivationVersion, action, status, request_id AS requestId,
            `operator`, deadline_at AS deadlineAt, created_at AS createdAt, completed_at AS completedAt
            """;

    String TARGET_COLUMNS = """
            id, activation_id AS activationId, runtime_node_id AS runtimeNodeId,
            required, status, captured_at AS capturedAt, updated_at AS updatedAt,
            waived_by AS waivedBy, waive_reason AS waiveReason
            """;

    String OUTBOX_COLUMNS = """
            id, activation_id AS activationId, aggregate_key AS aggregateKey,
            activation_version AS activationVersion, CAST(payload_json AS CHAR) AS payloadJson,
            payload_bytes AS payloadBytes, status, attempt_count AS attemptCount,
            next_attempt_at AS nextAttemptAt, lease_owner AS leaseOwner, lease_until AS leaseUntil,
            fencing_token AS fencingToken, last_error_masked AS lastErrorMasked,
            created_at AS createdAt, updated_at AS updatedAt, projected_at AS projectedAt
            """;

    @Select("SELECT " + RELEASE_COLUMNS + " FROM mock_release ORDER BY created_at DESC, id DESC")
    List<ReleaseRecord> selectAllReleases();

    @Select("SELECT " + RELEASE_COLUMNS + " FROM mock_release WHERE id = #{id}")
    ReleaseRecord selectRelease(@Param("id") String id);

    @Select("SELECT " + RELEASE_COLUMNS + " FROM mock_release WHERE id = #{id} FOR UPDATE")
    ReleaseRecord lockRelease(@Param("id") String id);

    @Select("SELECT " + RELEASE_COLUMNS + " FROM mock_release WHERE release_code = #{code}")
    ReleaseRecord selectReleaseByCode(@Param("code") String code);

    @Insert("""
            INSERT INTO mock_release (
                id, release_code, environment, app_code, status,
                snapshot_json, snapshot_bytes, checksum, schema_version,
                signature, signature_key_id, signature_algorithm, release_note,
                created_by, created_at
            ) VALUES (
                #{id}, #{releaseCode}, #{environment}, #{appCode}, 'PREPARING',
                CAST(#{snapshotJson} AS JSON), #{snapshotBytes}, #{checksum}, #{schemaVersion},
                #{signature}, #{signatureKeyId}, #{signatureAlgorithm}, #{releaseNote},
                #{createdBy}, #{createdAt}
            )
            """)
    int insertRelease(
            @Param("id") String id,
            @Param("releaseCode") String releaseCode,
            @Param("environment") String environment,
            @Param("appCode") String appCode,
            @Param("snapshotJson") String snapshotJson,
            @Param("snapshotBytes") byte[] snapshotBytes,
            @Param("checksum") String checksum,
            @Param("schemaVersion") String schemaVersion,
            @Param("signature") byte[] signature,
            @Param("signatureKeyId") String signatureKeyId,
            @Param("signatureAlgorithm") String signatureAlgorithm,
            @Param("releaseNote") String releaseNote,
            @Param("createdBy") String createdBy,
            @Param("createdAt") Instant createdAt);

    @Insert("""
            INSERT INTO mock_release_item (release_id, item_type, object_id, object_version_id)
            VALUES (#{releaseId}, #{itemType}, #{objectId}, #{objectVersionId})
            """)
    int insertReleaseItem(
            @Param("releaseId") String releaseId,
            @Param("itemType") String itemType,
            @Param("objectId") long objectId,
            @Param("objectVersionId") long objectVersionId);

    @Select("""
            SELECT id, release_id AS releaseId, item_type AS itemType,
                object_id AS objectId, object_version_id AS objectVersionId
            FROM mock_release_item WHERE release_id = #{releaseId}
            ORDER BY item_type, object_id, object_version_id
            """)
    List<ReleaseItemRecord> selectReleaseItems(@Param("releaseId") String releaseId);

    @Update("UPDATE mock_release SET status = 'READY' WHERE id = #{id} AND status = 'PREPARING'")
    int markReleaseReady(@Param("id") String id);

    @Update("""
            UPDATE mock_release SET status = 'FAILED', failure_reason = #{reason}
            WHERE id = #{id} AND status = 'PREPARING'
            """)
    int markReleaseFailed(@Param("id") String id, @Param("reason") String reason);

    @Update("""
            UPDATE mock_release SET status = 'PUBLISHED', published_by = #{operator}, published_at = #{at}
            WHERE id = #{id} AND status IN ('READY', 'PARTIAL', 'PUBLISHED')
            """)
    int markReleasePublished(
            @Param("id") String id,
            @Param("operator") String operator,
            @Param("at") Instant at);

    @Update("""
            UPDATE mock_release SET status = 'PARTIAL', failure_reason = #{reason}
            WHERE id = #{id} AND status IN ('READY', 'PUBLISHED', 'PARTIAL')
            """)
    int markReleasePartial(@Param("id") String id, @Param("reason") String reason);

    @Select({"<script>", """
            SELECT sv.id AS scenarioVersionId, s.id AS scenarioId, s.scenario_code AS scenarioCode,
                sv.status AS scenarioStatus, s.status AS scenarioRootStatus,
                cv.id AS contractVersionId, cv.status AS contractStatus,
                sv.flow_definition_version_id AS flowDefinitionVersionId,
                fdv.status AS flowVersionStatus,
                fdv.version_no AS flowVersionNo,
                fdv.validation_status AS flowValidationStatus,
                CAST(fdv.compiled_json AS CHAR) AS flowCompiledJson,
                fdv.checksum AS flowChecksum,
                fd.id AS flowDefinitionId, fd.provider_id AS flowProviderId,
                fd.flow_code AS flowCode, fdv.initial_state AS flowInitialState,
                fdv.ttl_seconds AS flowTtlSeconds,
                CAST(fdv.participant_apis_json AS CHAR) AS flowParticipantApisJson,
                CAST(fdv.variables_json AS CHAR) AS flowVariablesJson,
                CAST(fdv.transitions_json AS CHAR) AS flowTransitionsJson,
                sv.priority,
                sv.effective_from AS effectiveFrom, sv.effective_to AS effectiveTo,
                CAST(sv.scope_json AS CHAR) AS scopeJson,
                CAST(sv.match_rule_json AS CHAR) AS matchRuleJson,
                CAST(sv.response_json AS CHAR) AS responseJson,
                CAST(sv.callback_json AS CHAR) AS callbackJson,
                CAST(sv.compiled_json AS CHAR) AS compiledJson,
                sv.checksum AS scenarioChecksum, sv.validation_status AS validationStatus,
                p.id AS providerId, p.provider_code AS providerCode, p.status AS providerStatus,
                a.api_code AS apiCode, a.id AS apiId, a.status AS apiStatus,
                a.http_method AS httpMethod, a.path, a.content_type AS contentType,
                CAST(cv.request_schema_json AS CHAR) AS requestSchemaJson,
                CAST(cv.response_schema_json AS CHAR) AS responseSchemaJson,
                CAST(cv.business_key_extractor_json AS CHAR) AS businessKeyExtractorJson
            FROM mock_scenario_version sv
            JOIN mock_scenario s ON s.id = sv.scenario_id
            JOIN mock_provider p ON p.id = s.provider_id
            JOIN mock_api a ON a.id = s.api_id
            JOIN mock_contract_version cv ON cv.id = sv.contract_version_id
            LEFT JOIN mock_flow_definition_version fdv ON fdv.id = sv.flow_definition_version_id
            LEFT JOIN mock_flow_definition fd ON fd.id = fdv.flow_definition_id
            WHERE sv.id IN
            """, "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>", """
            ORDER BY p.provider_code, a.api_code, sv.priority DESC, s.scenario_code, sv.id
            """, "</script>"})
    List<ReleaseSourceRecord> selectReleaseSources(@Param("ids") List<Long> ids);

    @Insert("""
            INSERT IGNORE INTO mock_active_release (
                environment, app_code, release_id, activation_version, state
            ) VALUES (#{environment}, #{app}, NULL, 0, 'APPLIED')
            """)
    int ensureActive(@Param("environment") String environment, @Param("app") String app);

    @Select("""
            SELECT 
            """ + ACTIVE_COLUMNS + """
            FROM mock_active_release WHERE environment = #{environment} AND app_code = #{app}
            FOR UPDATE
            """)
    ActiveReleaseRecord lockActive(@Param("environment") String environment, @Param("app") String app);

    @Select("""
            SELECT 
            """ + ACTIVE_COLUMNS + """
            FROM mock_active_release WHERE environment = #{environment} AND app_code = #{app}
            """)
    ActiveReleaseRecord selectActive(@Param("environment") String environment, @Param("app") String app);

    @Update("""
            UPDATE mock_active_release
            SET release_id = #{releaseId}, activation_version = #{newVersion}, state = 'ACTIVATING',
                updated_at = #{updatedAt}
            WHERE environment = #{environment} AND app_code = #{app}
              AND activation_version = #{expectedVersion}
            """)
    int activate(
            @Param("environment") String environment,
            @Param("app") String app,
            @Param("releaseId") String releaseId,
            @Param("expectedVersion") long expectedVersion,
            @Param("newVersion") long newVersion,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE mock_active_release SET state = #{state}, updated_at = #{at}
            WHERE environment = #{environment} AND app_code = #{app}
              AND release_id = #{releaseId} AND activation_version = #{version}
            """)
    int updateActiveState(
            @Param("environment") String environment,
            @Param("app") String app,
            @Param("releaseId") String releaseId,
            @Param("version") long version,
            @Param("state") String state,
            @Param("at") Instant at);

    @Insert("""
            INSERT INTO mock_release_activation (
                id, environment, app_code, from_release_id, to_release_id,
                from_activation_version, to_activation_version, action, status,
                request_id, `operator`, deadline_at, created_at
            ) VALUES (
                #{id}, #{environment}, #{app}, #{fromReleaseId,jdbcType=VARCHAR}, #{toReleaseId},
                #{fromVersion}, #{toVersion}, #{action}, 'PENDING',
                #{requestId}, #{operator}, #{deadlineAt}, #{createdAt}
            )
            """)
    int insertActivation(
            @Param("id") String id,
            @Param("environment") String environment,
            @Param("app") String app,
            @Param("fromReleaseId") String fromReleaseId,
            @Param("toReleaseId") String toReleaseId,
            @Param("fromVersion") long fromVersion,
            @Param("toVersion") long toVersion,
            @Param("action") String action,
            @Param("requestId") String requestId,
            @Param("operator") String operator,
            @Param("deadlineAt") Instant deadlineAt,
            @Param("createdAt") Instant createdAt);

    @Select("SELECT " + ACTIVATION_COLUMNS + " FROM mock_release_activation WHERE id = #{id}")
    ReleaseActivationRecord selectActivation(@Param("id") String id);

    @Select("SELECT " + ACTIVATION_COLUMNS + " FROM mock_release_activation WHERE id = #{id} FOR UPDATE")
    ReleaseActivationRecord lockActivation(@Param("id") String id);

    @Select("SELECT " + ACTIVATION_COLUMNS + " FROM mock_release_activation WHERE request_id = #{requestId}")
    ReleaseActivationRecord selectActivationByRequestId(@Param("requestId") String requestId);

    @Select("""
            SELECT 
            """ + ACTIVATION_COLUMNS + """
            FROM mock_release_activation
            WHERE environment = #{environment} AND app_code = #{app}
              AND to_activation_version = #{version}
            """)
    ReleaseActivationRecord selectActivationByVersion(
            @Param("environment") String environment,
            @Param("app") String app,
            @Param("version") long version);

    @Select("""
            SELECT 
            """ + ACTIVATION_COLUMNS + """
            FROM mock_release_activation
            WHERE status IN ('PENDING', 'PROJECTED') AND deadline_at <= #{now}
            ORDER BY deadline_at, id
            """)
    List<ReleaseActivationRecord> selectExpiredActivations(@Param("now") Instant now);

    @Select("""
            SELECT 
            """ + ACTIVATION_COLUMNS + """
            FROM mock_release_activation
            WHERE status IN ('PENDING', 'PROJECTED', 'PARTIAL')
            ORDER BY created_at, id
            """)
    List<ReleaseActivationRecord> selectOpenActivations();

    @Update("""
            UPDATE mock_release_activation
            SET status = #{status}, completed_at = #{completedAt,jdbcType=TIMESTAMP}
            WHERE id = #{id} AND status IN ('PENDING', 'PROJECTED', 'PARTIAL')
            """)
    int updateActivationStatus(
            @Param("id") String id,
            @Param("status") String status,
            @Param("completedAt") Instant completedAt);

    @Insert({"<script>", """
            INSERT INTO mock_activation_target_node (
                activation_id, runtime_node_id, required, status, captured_at
            ) VALUES
            """, "<foreach collection='nodes' item='node' separator=','>",
            "(#{activationId}, #{node.nodeId}, TRUE, 'WAITING', #{capturedAt})",
            "</foreach>", "</script>"})
    int insertTargets(
            @Param("activationId") String activationId,
            @Param("nodes") List<RuntimeNodeDiscoveryPort.RuntimeNode> nodes,
            @Param("capturedAt") Instant capturedAt);

    @Select("SELECT " + TARGET_COLUMNS + " FROM mock_activation_target_node WHERE activation_id = #{activationId} ORDER BY runtime_node_id")
    List<ActivationTargetRecord> selectTargets(@Param("activationId") String activationId);

    @Select("""
            SELECT 
            """ + TARGET_COLUMNS + """
            FROM mock_activation_target_node WHERE activation_id = #{activationId}
            ORDER BY runtime_node_id FOR UPDATE
            """)
    List<ActivationTargetRecord> lockTargets(@Param("activationId") String activationId);

    @Update("""
            UPDATE mock_activation_target_node SET status = #{status}, updated_at = #{at}
            WHERE activation_id = #{activationId} AND runtime_node_id = #{nodeId}
              AND required = TRUE
              AND (status IN ('WAITING', 'FAILED') OR (status = 'READY' AND #{status} = 'READY'))
            """)
    int updateTargetAck(
            @Param("activationId") String activationId,
            @Param("nodeId") String nodeId,
            @Param("status") String status,
            @Param("at") Instant at);

    @Update("""
            UPDATE mock_activation_target_node
            SET status = 'WAIVED', required = FALSE, waived_by = #{operator},
                waive_reason = #{reason}, updated_at = #{at}
            WHERE activation_id = #{activationId} AND runtime_node_id = #{nodeId}
              AND required = TRUE AND status IN ('WAITING', 'FAILED')
            """)
    int waiveTarget(
            @Param("activationId") String activationId,
            @Param("nodeId") String nodeId,
            @Param("operator") String operator,
            @Param("reason") String reason,
            @Param("at") Instant at);

    @Update("""
            UPDATE mock_activation_target_node
            SET status = 'LEFT', required = FALSE, updated_at = #{at}
            WHERE activation_id = #{activationId} AND runtime_node_id = #{nodeId}
              AND required = TRUE AND status IN ('WAITING', 'FAILED')
            """)
    int markTargetLeft(
            @Param("activationId") String activationId,
            @Param("nodeId") String nodeId,
            @Param("at") Instant at);

    @Insert("""
            INSERT INTO mock_release_outbox (
                activation_id, aggregate_key, activation_version,
                payload_json, payload_bytes, status, next_attempt_at
            ) VALUES (
                #{activationId}, #{aggregateKey}, #{activationVersion},
                CAST(#{payloadJson} AS JSON), #{payloadBytes}, 'NEW', #{createdAt}
            )
            """)
    int insertOutbox(
            @Param("activationId") String activationId,
            @Param("aggregateKey") String aggregateKey,
            @Param("activationVersion") long activationVersion,
            @Param("payloadJson") String payloadJson,
            @Param("payloadBytes") byte[] payloadBytes,
            @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT 
            """ + OUTBOX_COLUMNS + """
            FROM mock_release_outbox
            WHERE status = 'NEW' AND next_attempt_at <= #{now}
              AND (lease_until IS NULL OR lease_until < #{now})
            ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    ReleaseOutboxRecord lockNextOutbox(@Param("now") Instant now);

    @Select("SELECT " + OUTBOX_COLUMNS + " FROM mock_release_outbox WHERE id = #{id}")
    ReleaseOutboxRecord selectOutbox(@Param("id") long id);

    @Select("SELECT " + OUTBOX_COLUMNS + " FROM mock_release_outbox WHERE id = #{id} FOR UPDATE")
    ReleaseOutboxRecord lockOutbox(@Param("id") long id);

    @Update("""
            UPDATE mock_release_outbox
            SET lease_owner = #{owner}, lease_until = #{leaseUntil},
                fencing_token = fencing_token + 1, attempt_count = attempt_count + 1
            WHERE id = #{id} AND status = 'NEW' AND fencing_token = #{oldFence}
            """)
    int claimOutbox(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("oldFence") long oldFence);

    @Update("""
            UPDATE mock_release_outbox
            SET status = 'PROJECTED', projected_at = #{at}, lease_owner = NULL, lease_until = NULL,
                last_error_masked = NULL
            WHERE id = #{id} AND status = 'NEW' AND lease_owner = #{owner}
              AND fencing_token = #{fence}
            """)
    int finishOutbox(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("fence") long fence,
            @Param("at") Instant at);

    @Update("""
            UPDATE mock_release_outbox
            SET status = #{status}, next_attempt_at = #{nextAttemptAt},
                lease_owner = NULL, lease_until = NULL, last_error_masked = #{error}
            WHERE id = #{id} AND status = 'NEW' AND lease_owner = #{owner}
              AND fencing_token = #{fence}
            """)
    int failOutbox(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("fence") long fence,
            @Param("status") String status,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") String error);

    @Insert("""
            INSERT INTO mock_runtime_activation_ack (
                environment, app_code, runtime_node_id, release_id,
                activation_version, status, error_masked, reported_at
            ) VALUES (
                #{environment}, #{app}, #{nodeId}, #{releaseId},
                #{version}, #{status}, #{error}, #{reportedAt}
            ) ON DUPLICATE KEY UPDATE
                release_id = VALUES(release_id),
                status = IF(status = 'READY', 'READY', VALUES(status)),
                error_masked = VALUES(error_masked), reported_at = VALUES(reported_at)
            """)
    int upsertAck(
            @Param("environment") String environment,
            @Param("app") String app,
            @Param("nodeId") String nodeId,
            @Param("releaseId") String releaseId,
            @Param("version") long version,
            @Param("status") String status,
            @Param("error") String error,
            @Param("reportedAt") Instant reportedAt);

    @Update("""
            UPDATE mock_scenario_version SET status = 'PUBLISHED', published_at = #{at}
            WHERE status = 'APPROVED' AND id IN (
                SELECT object_version_id FROM mock_release_item
                WHERE release_id = #{releaseId} AND item_type = 'SCENARIO'
            )
            """)
    int publishScenarioItems(@Param("releaseId") String releaseId, @Param("at") Instant at);
}
