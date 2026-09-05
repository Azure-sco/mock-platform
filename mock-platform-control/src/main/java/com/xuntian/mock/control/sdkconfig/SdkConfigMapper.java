package com.xuntian.mock.control.sdkconfig;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface SdkConfigMapper {

    @Insert("""
            INSERT INTO mock_sdk_config_envelope (
                app_code, environment, config_version, routing_json, security_policy_refs_json,
                security_policy_payloads_encrypted, effective_at, expire_at, checksum,
                validation_status, status, source_audit_ref, created_by
            ) VALUES (
                #{appCode}, #{environment}, #{configVersion}, CAST(#{routingJson} AS JSON),
                CAST(#{policyRefsJson} AS JSON), #{protectedPolicyPayloads}, #{effectiveAt}, #{expireAt},
                #{checksum}, 'NOT_VALIDATED', 'DRAFT', #{sourceAuditRef}, #{createdBy}
            )
            """)
    int insertEnvelope(
            @Param("appCode") String appCode,
            @Param("environment") String environment,
            @Param("configVersion") long configVersion,
            @Param("routingJson") String routingJson,
            @Param("policyRefsJson") String policyRefsJson,
            @Param("protectedPolicyPayloads") String protectedPolicyPayloads,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("expireAt") Instant expireAt,
            @Param("checksum") String checksum,
            @Param("sourceAuditRef") String sourceAuditRef,
            @Param("createdBy") String createdBy);

    String SELECT_COLUMNS = """
            id, app_code AS appCode, environment, config_version AS configVersion,
            routing_json AS routingJson, security_policy_refs_json AS securityPolicyRefsJson,
            security_policy_payloads_encrypted AS securityPolicyPayloadsEncrypted,
            effective_at AS effectiveAt, expire_at AS expireAt, checksum, signature,
            signature_key_id AS signatureKeyId, signature_algorithm AS signatureAlgorithm,
            validation_status AS validationStatus, status,
            approval_request_id AS approvalRequestId, source_audit_ref AS sourceAuditRef,
            created_by AS createdBy, created_at AS createdAt,
            published_by AS publishedBy, published_at AS publishedAt
            """;

    @Select("SELECT " + SELECT_COLUMNS + " FROM mock_sdk_config_envelope WHERE id = #{id}")
    SdkConfigEnvelopeRecord selectEnvelope(long id);

    @Select("SELECT " + SELECT_COLUMNS + " FROM mock_sdk_config_envelope WHERE id = #{id} FOR UPDATE")
    SdkConfigEnvelopeRecord lockEnvelope(long id);

    @Select("""
            SELECT
            """ + SELECT_COLUMNS + """
            FROM mock_sdk_config_envelope
            WHERE app_code = #{appCode} AND environment = #{environment}
            ORDER BY config_version DESC
            """)
    List<SdkConfigEnvelopeRecord> selectEnvelopes(
            @Param("appCode") String appCode,
            @Param("environment") String environment);

    @Select("""
            SELECT COALESCE(MAX(config_version), 0) + 1 FROM mock_sdk_config_envelope
            WHERE app_code = #{appCode} AND environment = #{environment}
            """)
    long nextConfigVersion(@Param("appCode") String appCode, @Param("environment") String environment);

    @Update("""
            UPDATE mock_sdk_config_envelope
            SET validation_status = 'VALID', status = 'VALIDATED',
                signature = #{signature}, signature_key_id = #{signatureKeyId},
                signature_algorithm = #{signatureAlgorithm}
            WHERE id = #{id} AND status = 'DRAFT' AND checksum = #{checksum}
            """)
    int markValidated(
            @Param("id") long id,
            @Param("checksum") String checksum,
            @Param("signature") String signature,
            @Param("signatureKeyId") String signatureKeyId,
            @Param("signatureAlgorithm") String signatureAlgorithm);

    @Update("""
            UPDATE mock_sdk_config_envelope
            SET status = 'PENDING_APPROVAL', approval_request_id = #{approvalRequestId}
            WHERE id = #{id} AND status = 'VALIDATED' AND checksum = #{checksum}
            """)
    int markPendingApproval(
            @Param("id") long id,
            @Param("approvalRequestId") long approvalRequestId,
            @Param("checksum") String checksum);

    @Update("""
            UPDATE mock_sdk_config_envelope
            SET status = 'APPROVED'
            WHERE id = #{id} AND status = 'PENDING_APPROVAL'
              AND approval_request_id = #{approvalRequestId} AND checksum = #{checksum}
            """)
    int markApproved(
            @Param("id") long id,
            @Param("approvalRequestId") long approvalRequestId,
            @Param("checksum") String checksum);

    @Update("""
            UPDATE mock_sdk_config_envelope
            SET status = 'VALIDATED'
            WHERE id = #{id} AND status = 'PENDING_APPROVAL'
              AND approval_request_id = #{approvalRequestId} AND checksum = #{checksum}
            """)
    int markRejected(
            @Param("id") long id,
            @Param("approvalRequestId") long approvalRequestId,
            @Param("checksum") String checksum);

    @Update("""
            UPDATE mock_sdk_config_envelope SET status = 'PUBLISHING'
            WHERE id = #{id} AND status = 'APPROVED' AND checksum = #{checksum}
              AND signature IS NOT NULL AND signature_key_id IS NOT NULL AND signature_algorithm IS NOT NULL
            """)
    int markPublishing(@Param("id") long id, @Param("checksum") String checksum);

    @Update("""
            UPDATE mock_sdk_config_envelope
            SET status = 'PUBLISHED', published_by = #{operator}, published_at = #{publishedAt}
            WHERE id = #{id} AND status = 'PUBLISHING'
            """)
    int markPublished(
            @Param("id") long id,
            @Param("operator") String operator,
            @Param("publishedAt") Instant publishedAt);

    @Insert("""
            INSERT INTO mock_sdk_config_activation (
                id, app_code, environment, sdk_config_envelope_id, from_config_version,
                to_config_version, status, request_id, `operator`, created_at
            ) VALUES (
                #{id}, #{appCode}, #{environment}, #{envelopeId}, #{fromConfigVersion},
                #{toConfigVersion}, 'PENDING', #{requestId}, #{operator}, #{createdAt}
            )
            """)
    int insertActivation(
            @Param("id") String id,
            @Param("appCode") String appCode,
            @Param("environment") String environment,
            @Param("envelopeId") long envelopeId,
            @Param("fromConfigVersion") Long fromConfigVersion,
            @Param("toConfigVersion") long toConfigVersion,
            @Param("requestId") String requestId,
            @Param("operator") String operator,
            @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT id, app_code AS appCode, environment, sdk_config_envelope_id AS sdkConfigEnvelopeId,
                   from_config_version AS fromConfigVersion, to_config_version AS toConfigVersion,
                   status, request_id AS requestId, `operator`, created_at AS createdAt,
                   completed_at AS completedAt
            FROM mock_sdk_config_activation WHERE id = #{id}
            """)
    SdkConfigActivationRecord selectActivation(String id);

    @Select("""
            SELECT id, app_code AS appCode, environment, sdk_config_envelope_id AS sdkConfigEnvelopeId,
                   from_config_version AS fromConfigVersion, to_config_version AS toConfigVersion,
                   status, request_id AS requestId, `operator`, created_at AS createdAt,
                   completed_at AS completedAt
            FROM mock_sdk_config_activation WHERE id = #{id} FOR UPDATE
            """)
    SdkConfigActivationRecord lockActivation(String id);

    @Select("""
            SELECT id, app_code AS appCode, environment, sdk_config_envelope_id AS sdkConfigEnvelopeId,
                   from_config_version AS fromConfigVersion, to_config_version AS toConfigVersion,
                   status, request_id AS requestId, `operator`, created_at AS createdAt,
                   completed_at AS completedAt
            FROM mock_sdk_config_activation WHERE request_id = #{requestId}
            """)
    SdkConfigActivationRecord selectActivationByRequest(String requestId);

    @Select("""
            SELECT app_code AS appCode, environment, desired_envelope_id AS desiredEnvelopeId,
                   desired_config_version AS desiredConfigVersion,
                   last_applied_envelope_id AS lastAppliedEnvelopeId,
                   last_applied_config_version AS lastAppliedConfigVersion,
                   activation_id AS activationId, state, updated_at AS updatedAt
            FROM mock_active_sdk_config WHERE app_code = #{appCode} AND environment = #{environment}
            FOR UPDATE
            """)
    ActiveSdkConfigRecord lockActive(
            @Param("appCode") String appCode,
            @Param("environment") String environment);

    @Select("""
            SELECT app_code AS appCode, environment, desired_envelope_id AS desiredEnvelopeId,
                   desired_config_version AS desiredConfigVersion,
                   last_applied_envelope_id AS lastAppliedEnvelopeId,
                   last_applied_config_version AS lastAppliedConfigVersion,
                   activation_id AS activationId, state, updated_at AS updatedAt
            FROM mock_active_sdk_config WHERE app_code = #{appCode} AND environment = #{environment}
            """)
    ActiveSdkConfigRecord selectActive(
            @Param("appCode") String appCode,
            @Param("environment") String environment);

    @Insert("""
            INSERT INTO mock_active_sdk_config (
                app_code, environment, desired_envelope_id, desired_config_version,
                last_applied_envelope_id, last_applied_config_version, activation_id, state, updated_at
            ) VALUES (
                #{appCode}, #{environment}, #{envelopeId}, #{configVersion},
                NULL, NULL, #{activationId}, 'ACTIVATING', #{now}
            )
            """)
    int insertActive(
            @Param("appCode") String appCode,
            @Param("environment") String environment,
            @Param("envelopeId") long envelopeId,
            @Param("configVersion") long configVersion,
            @Param("activationId") String activationId,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_active_sdk_config
            SET desired_envelope_id = #{envelopeId}, desired_config_version = #{newConfigVersion},
                activation_id = #{activationId}, state = 'ACTIVATING', updated_at = #{now}
            WHERE app_code = #{appCode} AND environment = #{environment}
              AND desired_config_version = #{expectedConfigVersion}
              AND (state <> 'PARTIAL' OR #{allowPartialRecovery})
            """)
    int updateActiveDesired(
            @Param("appCode") String appCode,
            @Param("environment") String environment,
            @Param("envelopeId") long envelopeId,
            @Param("newConfigVersion") long newConfigVersion,
            @Param("expectedConfigVersion") long expectedConfigVersion,
            @Param("activationId") String activationId,
            @Param("now") Instant now,
            @Param("allowPartialRecovery") boolean allowPartialRecovery);

    @Insert("""
            INSERT INTO mock_sdk_config_target_instance (
                activation_id, sdk_instance_id, required, status, captured_at, updated_at
            ) VALUES (#{activationId}, #{instanceId}, TRUE, 'WAITING', #{now}, #{now})
            """)
    int insertTarget(
            @Param("activationId") String activationId,
            @Param("instanceId") String instanceId,
            @Param("now") Instant now);

    @Select("""
            SELECT id, activation_id AS activationId, sdk_instance_id AS sdkInstanceId,
                   required, status, captured_at AS capturedAt, updated_at AS updatedAt,
                   waived_by AS waivedBy, waive_reason AS waiveReason
            FROM mock_sdk_config_target_instance WHERE activation_id = #{activationId}
            ORDER BY sdk_instance_id
            """)
    List<SdkConfigTargetRecord> selectTargets(String activationId);

    @Update("""
            UPDATE mock_sdk_config_target_instance
            SET status = #{status}, updated_at = #{now}
            WHERE activation_id = #{activationId} AND sdk_instance_id = #{instanceId}
              AND required = TRUE AND status IN ('WAITING', 'REJECTED', 'APPLIED')
            """)
    int updateTargetFromEvent(
            @Param("activationId") String activationId,
            @Param("instanceId") String instanceId,
            @Param("status") String status,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_sdk_config_target_instance
            SET required = FALSE, status = 'LEFT', updated_at = #{now}
            WHERE activation_id = #{activationId} AND sdk_instance_id = #{instanceId}
              AND required = TRUE AND status IN ('WAITING', 'REJECTED')
            """)
    int markTargetLeft(
            @Param("activationId") String activationId,
            @Param("instanceId") String instanceId,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_sdk_config_target_instance
            SET required = FALSE, status = 'WAIVED', waived_by = #{operator},
                waive_reason = #{reason}, updated_at = #{now}
            WHERE activation_id = #{activationId} AND sdk_instance_id = #{instanceId}
              AND required = TRUE AND status IN ('WAITING', 'REJECTED')
            """)
    int waiveTarget(
            @Param("activationId") String activationId,
            @Param("instanceId") String instanceId,
            @Param("operator") String operator,
            @Param("reason") String reason,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO mock_sdk_config_event (
                app_code, environment, sdk_instance_id, sdk_config_envelope_id,
                sdk_config_activation_id, old_config_version, new_config_version,
                security_policy_refs_json, status, effective_at, error_masked,
                source_audit_ref, received_at
            ) VALUES (
                #{appCode}, #{environment}, #{instanceId}, #{envelopeId}, #{activationId},
                #{oldConfigVersion}, #{newConfigVersion}, CAST(#{policyRefsJson} AS JSON),
                #{status}, #{effectiveAt}, #{errorMasked}, #{sourceAuditRef}, #{receivedAt}
            )
            """)
    int insertEvent(
            @Param("appCode") String appCode,
            @Param("environment") String environment,
            @Param("instanceId") String instanceId,
            @Param("envelopeId") long envelopeId,
            @Param("activationId") String activationId,
            @Param("oldConfigVersion") Long oldConfigVersion,
            @Param("newConfigVersion") long newConfigVersion,
            @Param("policyRefsJson") String policyRefsJson,
            @Param("status") String status,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("errorMasked") String errorMasked,
            @Param("sourceAuditRef") String sourceAuditRef,
            @Param("receivedAt") Instant receivedAt);

    @Select("""
            SELECT COUNT(*) FROM mock_sdk_config_event
            WHERE sdk_config_activation_id = #{activationId} AND sdk_instance_id = #{instanceId}
              AND status = #{status} AND new_config_version = #{newConfigVersion}
            """)
    int countMatchingEvent(
            @Param("activationId") String activationId,
            @Param("instanceId") String instanceId,
            @Param("status") String status,
            @Param("newConfigVersion") long newConfigVersion);

    @Update("""
            UPDATE mock_sdk_config_activation SET status = 'PROJECTED'
            WHERE id = #{activationId} AND status = 'PENDING'
            """)
    int markActivationProjected(String activationId);

    @Update("""
            UPDATE mock_sdk_config_activation SET status = 'APPLIED', completed_at = #{now}
            WHERE id = #{activationId} AND status IN ('PENDING', 'PROJECTED', 'PARTIAL')
            """)
    int markActivationApplied(@Param("activationId") String activationId, @Param("now") Instant now);

    @Update("""
            UPDATE mock_active_sdk_config
            SET last_applied_envelope_id = desired_envelope_id,
                last_applied_config_version = desired_config_version,
                state = 'APPLIED', updated_at = #{now}
            WHERE activation_id = #{activationId} AND state IN ('ACTIVATING', 'PARTIAL')
            """)
    int markActiveApplied(@Param("activationId") String activationId, @Param("now") Instant now);

    @Update("""
            UPDATE mock_sdk_config_activation SET status = 'PARTIAL'
            WHERE id = #{activationId} AND status IN ('PENDING', 'PROJECTED')
            """)
    int markActivationPartial(String activationId);

    @Update("""
            UPDATE mock_active_sdk_config SET state = 'PARTIAL', updated_at = #{now}
            WHERE activation_id = #{activationId} AND state = 'ACTIVATING'
            """)
    int markActivePartial(@Param("activationId") String activationId, @Param("now") Instant now);

    @Select("""
            SELECT id, app_code AS appCode, environment, sdk_config_envelope_id AS sdkConfigEnvelopeId,
                   from_config_version AS fromConfigVersion, to_config_version AS toConfigVersion,
                   status, request_id AS requestId, `operator`, created_at AS createdAt,
                   completed_at AS completedAt
            FROM mock_sdk_config_activation
            WHERE status IN ('PENDING', 'PROJECTED') AND created_at <= #{deadline}
            ORDER BY created_at
            """)
    List<SdkConfigActivationRecord> selectTimedOutActivations(Instant deadline);
}
