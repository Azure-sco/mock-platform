package com.xuntian.mock.control.securitypolicy;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface SecurityPolicyMapper {

    @Insert("""
            INSERT INTO mock_security_policy_version (
                policy_id, policy_type, scope_key, version_no, config_json_encrypted,
                checksum, status, source_audit_ref, created_by
            ) VALUES (
                #{policyId}, #{policyType}, #{scopeKey}, #{versionNo}, #{protectedConfig},
                #{checksum}, 'DRAFT', #{sourceAuditRef}, #{createdBy}
            )
            """)
    int insertVersion(
            @Param("policyId") String policyId,
            @Param("policyType") String policyType,
            @Param("scopeKey") String scopeKey,
            @Param("versionNo") int versionNo,
            @Param("protectedConfig") String protectedConfig,
            @Param("checksum") String checksum,
            @Param("sourceAuditRef") String sourceAuditRef,
            @Param("createdBy") String createdBy);

    @Select("""
            SELECT id, policy_id AS policyId, policy_type AS policyType, scope_key AS scopeKey,
                   version_no AS versionNo, config_json_encrypted AS configJsonEncrypted,
                   checksum, status, signature, signature_key_id AS signatureKeyId,
                   signature_algorithm AS signatureAlgorithm,
                   source_audit_ref AS sourceAuditRef, approval_request_id AS approvalRequestId,
                   created_by AS createdBy, created_at AS createdAt,
                   published_by AS publishedBy, published_at AS publishedAt
            FROM mock_security_policy_version WHERE id = #{id}
            """)
    SecurityPolicyVersionRecord selectVersionById(long id);

    @Select("""
            SELECT id, policy_id AS policyId, policy_type AS policyType, scope_key AS scopeKey,
                   version_no AS versionNo, config_json_encrypted AS configJsonEncrypted,
                   checksum, status, signature, signature_key_id AS signatureKeyId,
                   signature_algorithm AS signatureAlgorithm,
                   source_audit_ref AS sourceAuditRef, approval_request_id AS approvalRequestId,
                   created_by AS createdBy, created_at AS createdAt,
                   published_by AS publishedBy, published_at AS publishedAt
            FROM mock_security_policy_version WHERE id = #{id} FOR UPDATE
            """)
    SecurityPolicyVersionRecord lockVersionById(long id);

    @Select("""
            SELECT id, policy_id AS policyId, policy_type AS policyType, scope_key AS scopeKey,
                   version_no AS versionNo, config_json_encrypted AS configJsonEncrypted,
                   checksum, status, signature, signature_key_id AS signatureKeyId,
                   signature_algorithm AS signatureAlgorithm,
                   source_audit_ref AS sourceAuditRef, approval_request_id AS approvalRequestId,
                   created_by AS createdBy, created_at AS createdAt,
                   published_by AS publishedBy, published_at AS publishedAt
            FROM mock_security_policy_version
            WHERE policy_id = #{policyId}
            ORDER BY version_no DESC
            """)
    List<SecurityPolicyVersionRecord> selectVersionsByPolicy(String policyId);

    @Select("""
            <script>
            SELECT id, policy_id AS policyId, policy_type AS policyType, scope_key AS scopeKey,
                   version_no AS versionNo, config_json_encrypted AS configJsonEncrypted,
                   checksum, status, signature, signature_key_id AS signatureKeyId,
                   signature_algorithm AS signatureAlgorithm,
                   source_audit_ref AS sourceAuditRef, approval_request_id AS approvalRequestId,
                   created_by AS createdBy, created_at AS createdAt,
                   published_by AS publishedBy, published_at AS publishedAt
            FROM mock_security_policy_version
            WHERE id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            ORDER BY id
            </script>
            """)
    List<SecurityPolicyVersionRecord> selectVersionsByIds(@Param("ids") List<Long> ids);

    @Select("""
            <script>
            SELECT id, policy_id AS policyId, policy_type AS policyType, scope_key AS scopeKey,
                   version_no AS versionNo, config_json_encrypted AS configJsonEncrypted,
                   checksum, status, signature, signature_key_id AS signatureKeyId,
                   signature_algorithm AS signatureAlgorithm,
                   source_audit_ref AS sourceAuditRef, approval_request_id AS approvalRequestId,
                   created_by AS createdBy, created_at AS createdAt,
                   published_by AS publishedBy, published_at AS publishedAt
            FROM mock_security_policy_version
            WHERE 1 = 1
            <if test='policyType != null'>AND policy_type = #{policyType}</if>
            <if test='scopeKey != null'>AND scope_key = #{scopeKey}</if>
            ORDER BY policy_type, scope_key, version_no DESC
            </script>
            """)
    List<SecurityPolicyVersionRecord> selectAll(
            @Param("policyType") String policyType,
            @Param("scopeKey") String scopeKey);

    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM mock_security_policy_version WHERE policy_id = #{policyId}")
    int nextVersionNo(String policyId);

    @Update("""
            UPDATE mock_security_policy_version
            SET status = 'VALIDATED', signature = #{signature}, signature_key_id = #{signatureKeyId},
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
            UPDATE mock_security_policy_version
            SET approval_request_id = #{approvalRequestId}
            WHERE id = #{id} AND status = 'VALIDATED' AND checksum = #{checksum}
            """)
    int attachApproval(
            @Param("id") long id,
            @Param("checksum") String checksum,
            @Param("approvalRequestId") long approvalRequestId);

    @Update("""
            UPDATE mock_security_policy_version
            SET status = 'APPROVED'
            WHERE id = #{id} AND status = 'VALIDATED' AND checksum = #{checksum}
              AND approval_request_id = #{approvalRequestId}
            """)
    int markApproved(
            @Param("id") long id,
            @Param("checksum") String checksum,
            @Param("approvalRequestId") long approvalRequestId);

    @Update("""
            UPDATE mock_security_policy_version
            SET status = 'VALIDATED'
            WHERE id = #{id} AND status IN ('VALIDATED', 'APPROVED')
              AND approval_request_id = #{approvalRequestId}
            """)
    int markApprovalRejected(@Param("id") long id, @Param("approvalRequestId") long approvalRequestId);

    @Update("""
            UPDATE mock_security_policy_version
            SET status = 'PUBLISHED', published_by = #{operator}, published_at = #{publishedAt}
            WHERE id = #{id} AND status = 'APPROVED' AND checksum = #{checksum}
              AND signature IS NOT NULL AND signature_key_id IS NOT NULL AND signature_algorithm IS NOT NULL
            """)
    int markPublished(
            @Param("id") long id,
            @Param("checksum") String checksum,
            @Param("operator") String operator,
            @Param("publishedAt") Instant publishedAt);

    @Select("""
            SELECT id, policy_type AS policyType, scope_key AS scopeKey,
                   desired_policy_version_id AS desiredPolicyVersionId,
                   effective_policy_version_id AS effectivePolicyVersionId,
                   effect_mode AS effectMode, status, binding_version AS bindingVersion,
                   desired_at AS desiredAt, bound_at AS boundAt,
                   first_effective_release_id AS firstEffectiveReleaseId,
                   current_effective_release_id AS currentEffectiveReleaseId,
                   effective_activation_version AS effectiveActivationVersion,
                   sdk_effective_config_version AS sdkEffectiveConfigVersion,
                   effective_at AS effectiveAt, updated_by AS updatedBy, updated_at AS updatedAt
            FROM mock_security_policy_binding
            WHERE policy_type = #{policyType} AND scope_key = #{scopeKey}
            """)
    SecurityPolicyBindingRecord selectBinding(
            @Param("policyType") String policyType,
            @Param("scopeKey") String scopeKey);

    @Select("""
            SELECT id, policy_type AS policyType, scope_key AS scopeKey,
                   desired_policy_version_id AS desiredPolicyVersionId,
                   effective_policy_version_id AS effectivePolicyVersionId,
                   effect_mode AS effectMode, status, binding_version AS bindingVersion,
                   desired_at AS desiredAt, bound_at AS boundAt,
                   first_effective_release_id AS firstEffectiveReleaseId,
                   current_effective_release_id AS currentEffectiveReleaseId,
                   effective_activation_version AS effectiveActivationVersion,
                   sdk_effective_config_version AS sdkEffectiveConfigVersion,
                   effective_at AS effectiveAt, updated_by AS updatedBy, updated_at AS updatedAt
            FROM mock_security_policy_binding WHERE id = #{id}
            """)
    SecurityPolicyBindingRecord selectBindingById(String id);

    @Select("""
            SELECT id, policy_type AS policyType, scope_key AS scopeKey,
                   desired_policy_version_id AS desiredPolicyVersionId,
                   effective_policy_version_id AS effectivePolicyVersionId,
                   effect_mode AS effectMode, status, binding_version AS bindingVersion,
                   desired_at AS desiredAt, bound_at AS boundAt,
                   first_effective_release_id AS firstEffectiveReleaseId,
                   current_effective_release_id AS currentEffectiveReleaseId,
                   effective_activation_version AS effectiveActivationVersion,
                   sdk_effective_config_version AS sdkEffectiveConfigVersion,
                   effective_at AS effectiveAt, updated_by AS updatedBy, updated_at AS updatedAt
            FROM mock_security_policy_binding WHERE id = #{id} FOR UPDATE
            """)
    SecurityPolicyBindingRecord lockBindingById(String id);

    @Select("""
            SELECT id, policy_type AS policyType, scope_key AS scopeKey,
                   desired_policy_version_id AS desiredPolicyVersionId,
                   effective_policy_version_id AS effectivePolicyVersionId,
                   effect_mode AS effectMode, status, binding_version AS bindingVersion,
                   desired_at AS desiredAt, bound_at AS boundAt,
                   first_effective_release_id AS firstEffectiveReleaseId,
                   current_effective_release_id AS currentEffectiveReleaseId,
                   effective_activation_version AS effectiveActivationVersion,
                   sdk_effective_config_version AS sdkEffectiveConfigVersion,
                   effective_at AS effectiveAt, updated_by AS updatedBy, updated_at AS updatedAt
            FROM mock_security_policy_binding
            WHERE policy_type = #{policyType} AND scope_key = #{scopeKey}
            FOR UPDATE
            """)
    SecurityPolicyBindingRecord lockBinding(
            @Param("policyType") String policyType,
            @Param("scopeKey") String scopeKey);

    @Insert("""
            INSERT INTO mock_security_policy_binding (
                id, policy_type, scope_key, desired_policy_version_id, effect_mode,
                status, binding_version, desired_at, updated_by
            ) VALUES (
                #{id}, #{policyType}, #{scopeKey}, #{desiredPolicyVersionId}, #{effectMode},
                'INACTIVE', 0, #{now}, #{operator}
            )
            """)
    int insertBinding(
            @Param("id") String id,
            @Param("policyType") String policyType,
            @Param("scopeKey") String scopeKey,
            @Param("desiredPolicyVersionId") long desiredPolicyVersionId,
            @Param("effectMode") String effectMode,
            @Param("now") Instant now,
            @Param("operator") String operator);

    @Update("""
            UPDATE mock_security_policy_binding
            SET desired_policy_version_id = #{desiredPolicyVersionId},
                effective_policy_version_id = CASE WHEN #{boundImmediately} THEN #{desiredPolicyVersionId}
                                                   ELSE effective_policy_version_id END,
                status = CASE WHEN #{boundImmediately} THEN 'BOUND' ELSE 'PUBLISHING' END,
                binding_version = binding_version + 1,
                desired_at = #{now},
                bound_at = CASE WHEN #{boundImmediately} THEN #{now} ELSE bound_at END,
                sdk_effective_config_version = CASE WHEN #{effectMode} = 'SDK_CONFIG' THEN NULL
                                                    ELSE sdk_effective_config_version END,
                effective_at = NULL,
                updated_by = #{operator}
            WHERE id = #{id} AND binding_version = #{expectedBindingVersion}
            """)
    int publishBinding(
            @Param("id") String id,
            @Param("desiredPolicyVersionId") long desiredPolicyVersionId,
            @Param("effectMode") String effectMode,
            @Param("expectedBindingVersion") long expectedBindingVersion,
            @Param("boundImmediately") boolean boundImmediately,
            @Param("now") Instant now,
            @Param("operator") String operator);

    @Update("""
            UPDATE mock_security_policy_binding
            SET effective_policy_version_id = desired_policy_version_id,
                status = 'BOUND', bound_at = #{now}, updated_by = #{worker}
            WHERE id = #{bindingId} AND binding_version = #{bindingVersion}
              AND desired_policy_version_id = #{policyVersionId} AND status = 'PUBLISHING'
            """)
    int markAdmissionProjected(
            @Param("bindingId") String bindingId,
            @Param("bindingVersion") long bindingVersion,
            @Param("policyVersionId") long policyVersionId,
            @Param("now") Instant now,
            @Param("worker") String worker);

    @Select("""
            SELECT id, policy_type AS policyType, scope_key AS scopeKey,
                   desired_policy_version_id AS desiredPolicyVersionId,
                   effective_policy_version_id AS effectivePolicyVersionId,
                   effect_mode AS effectMode, status, binding_version AS bindingVersion,
                   desired_at AS desiredAt, bound_at AS boundAt,
                   first_effective_release_id AS firstEffectiveReleaseId,
                   current_effective_release_id AS currentEffectiveReleaseId,
                   effective_activation_version AS effectiveActivationVersion,
                   sdk_effective_config_version AS sdkEffectiveConfigVersion,
                   effective_at AS effectiveAt, updated_by AS updatedBy, updated_at AS updatedAt
            FROM mock_security_policy_binding
            WHERE effect_mode = 'LIVE_ADMISSION' AND status IN ('PUBLISHING', 'BOUND')
            ORDER BY id
            """)
    List<SecurityPolicyBindingRecord> selectLiveAdmissionBindings();

    @Insert("""
            INSERT INTO mock_runtime_policy_ack (
                runtime_node_id, binding_id, environment, app_code, policy_type,
                policy_version_id, binding_version, status, error_masked, reported_at
            ) VALUES (
                #{runtimeNodeId}, #{bindingId}, #{environment}, #{appCode}, 'APP_ACL',
                #{policyVersionId}, #{bindingVersion}, #{status}, #{errorMasked}, #{reportedAt}
            )
            ON DUPLICATE KEY UPDATE
                status = VALUES(status), error_masked = VALUES(error_masked), reported_at = VALUES(reported_at),
                policy_version_id = VALUES(policy_version_id), environment = VALUES(environment), app_code = VALUES(app_code)
            """)
    int upsertRuntimeAck(
            @Param("runtimeNodeId") String runtimeNodeId,
            @Param("bindingId") String bindingId,
            @Param("environment") String environment,
            @Param("appCode") String appCode,
            @Param("policyVersionId") long policyVersionId,
            @Param("bindingVersion") long bindingVersion,
            @Param("status") String status,
            @Param("errorMasked") String errorMasked,
            @Param("reportedAt") Instant reportedAt);

    @Select("""
            SELECT id, runtime_node_id AS runtimeNodeId, binding_id AS bindingId,
                   environment, app_code AS appCode, policy_type AS policyType,
                   policy_version_id AS policyVersionId, binding_version AS bindingVersion,
                   status, error_masked AS errorMasked, reported_at AS reportedAt
            FROM mock_runtime_policy_ack
            WHERE binding_id = #{bindingId} AND binding_version = #{bindingVersion}
            """)
    List<RuntimePolicyAckRecord> selectRuntimeAcks(
            @Param("bindingId") String bindingId,
            @Param("bindingVersion") long bindingVersion);

    @Update("""
            UPDATE mock_security_policy_binding
            SET effective_at = #{effectiveAt}, updated_by = #{worker}
            WHERE id = #{bindingId} AND binding_version = #{bindingVersion}
              AND effective_policy_version_id = desired_policy_version_id
              AND status = 'BOUND' AND effective_at IS NULL
            """)
    int markAdmissionEffective(
            @Param("bindingId") String bindingId,
            @Param("bindingVersion") long bindingVersion,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("worker") String worker);

    @Update("""
            <script>
            UPDATE mock_security_policy_binding
            SET sdk_effective_config_version = #{configVersion}, effective_at = #{effectiveAt},
                updated_by = #{worker}
            WHERE effect_mode = 'SDK_CONFIG' AND status = 'BOUND'
              AND desired_policy_version_id IN
              <foreach collection='policyVersionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            </script>
            """)
    int markSdkPoliciesEffective(
            @Param("policyVersionIds") List<Long> policyVersionIds,
            @Param("configVersion") long configVersion,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("worker") String worker);
}
