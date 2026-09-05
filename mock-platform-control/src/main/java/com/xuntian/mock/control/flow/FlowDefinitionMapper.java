package com.xuntian.mock.control.flow;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface FlowDefinitionMapper {

    String DEFINITION_COLUMNS = """
            id, provider_id AS providerId, flow_code AS flowCode, flow_name AS flowName,
            current_draft_version AS currentDraftVersion, status,
            created_by AS createdBy, created_at AS createdAt,
            updated_by AS updatedBy, updated_at AS updatedAt
            """;

    String VERSION_COLUMNS = """
            id, flow_definition_id AS flowDefinitionId, version_no AS versionNo, status,
            initial_state AS initialState, ttl_seconds AS ttlSeconds,
            CAST(participant_apis_json AS CHAR) AS participantApisJson,
            CAST(variables_json AS CHAR) AS variablesJson,
            CAST(transitions_json AS CHAR) AS transitionsJson,
            CAST(compiled_json AS CHAR) AS compiledJson,
            checksum, validation_status AS validationStatus,
            CAST(validation_result_json AS CHAR) AS validationResultJson,
            approval_request_id AS approvalRequestId, approved_at AS approvedAt,
            published_at AS publishedAt, deprecated_at AS deprecatedAt,
            created_by AS createdBy, created_at AS createdAt
            """;

    @Select("SELECT " + DEFINITION_COLUMNS + " FROM mock_flow_definition ORDER BY provider_id, flow_code")
    List<FlowDefinitionRecord> selectAll();

    @Select("SELECT " + DEFINITION_COLUMNS + " FROM mock_flow_definition WHERE id = #{id}")
    FlowDefinitionRecord selectById(@Param("id") long id);

    @Select("SELECT " + DEFINITION_COLUMNS + " FROM mock_flow_definition WHERE id = #{id} FOR UPDATE")
    FlowDefinitionRecord lockById(@Param("id") long id);

    @Select("""
            SELECT
            """ + DEFINITION_COLUMNS + """
            FROM mock_flow_definition
            WHERE provider_id = #{providerId} AND flow_code = #{flowCode}
            """)
    FlowDefinitionRecord selectByProviderAndCode(
            @Param("providerId") long providerId,
            @Param("flowCode") String flowCode);

    @Insert("""
            INSERT INTO mock_flow_definition (
                provider_id, flow_code, flow_name, status, created_by, updated_by
            ) VALUES (
                #{providerId}, #{flowCode}, #{flowName}, 'ENABLED', #{operator}, #{operator}
            )
            """)
    int insertDefinition(
            @Param("providerId") long providerId,
            @Param("flowCode") String flowCode,
            @Param("flowName") String flowName,
            @Param("operator") String operator);

    @Update("""
            UPDATE mock_flow_definition
            SET flow_name = #{flowName}, status = #{status}, updated_by = #{operator},
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id}
            """)
    int updateDefinition(
            @Param("id") long id,
            @Param("flowName") String flowName,
            @Param("status") String status,
            @Param("operator") String operator);

    @Update("""
            UPDATE mock_flow_definition
            SET current_draft_version = #{versionNo}, updated_by = #{operator},
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id}
            """)
    int updateCurrentDraft(
            @Param("id") long id,
            @Param("versionNo") int versionNo,
            @Param("operator") String operator);

    @Select("""
            SELECT COALESCE(MAX(version_no), 0) + 1
            FROM mock_flow_definition_version WHERE flow_definition_id = #{definitionId}
            """)
    int nextVersionNo(@Param("definitionId") long definitionId);

    @Insert("""
            INSERT INTO mock_flow_definition_version (
                flow_definition_id, version_no, status, initial_state, ttl_seconds,
                participant_apis_json, variables_json, transitions_json,
                checksum, validation_status, created_by
            ) VALUES (
                #{definitionId}, #{versionNo}, 'DRAFT', #{initialState}, #{ttlSeconds},
                CAST(#{participantApisJson} AS JSON), CAST(#{variablesJson} AS JSON),
                CAST(#{transitionsJson} AS JSON), #{checksum}, 'NOT_VALIDATED', #{operator}
            )
            """)
    int insertVersion(
            @Param("definitionId") long definitionId,
            @Param("versionNo") int versionNo,
            @Param("initialState") String initialState,
            @Param("ttlSeconds") long ttlSeconds,
            @Param("participantApisJson") String participantApisJson,
            @Param("variablesJson") String variablesJson,
            @Param("transitionsJson") String transitionsJson,
            @Param("checksum") String checksum,
            @Param("operator") String operator);

    @Select("SELECT " + VERSION_COLUMNS + " FROM mock_flow_definition_version WHERE id = #{id}")
    FlowDefinitionVersionRecord selectVersionById(@Param("id") long id);

    @Select("SELECT " + VERSION_COLUMNS + " FROM mock_flow_definition_version WHERE id = #{id} FOR UPDATE")
    FlowDefinitionVersionRecord lockVersionById(@Param("id") long id);

    @Select("""
            SELECT
            """ + VERSION_COLUMNS + """
            FROM mock_flow_definition_version
            WHERE flow_definition_id = #{definitionId}
            ORDER BY version_no DESC
            """)
    List<FlowDefinitionVersionRecord> selectVersions(@Param("definitionId") long definitionId);

    @Select("""
            SELECT
            """ + VERSION_COLUMNS + """
            FROM mock_flow_definition_version
            WHERE flow_definition_id = #{definitionId} AND version_no = #{versionNo}
            """)
    FlowDefinitionVersionRecord selectVersionByNumber(
            @Param("definitionId") long definitionId,
            @Param("versionNo") int versionNo);

    @Select("""
            SELECT DISTINCT
            """ + VERSION_COLUMNS + """
            FROM mock_flow_definition_version v
            JOIN mock_flow_instance i ON i.flow_definition_version_id = v.id
            WHERE v.flow_definition_id = #{definitionId}
              AND i.status = 'ACTIVE' AND i.expire_at > #{now}
            ORDER BY v.version_no
            """)
    List<FlowDefinitionVersionRecord> selectUnexpiredReferencedVersions(
            @Param("definitionId") long definitionId,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_flow_definition_version
            SET status = #{status}, validation_status = #{validationStatus},
                validation_result_json = CAST(#{resultJson} AS JSON),
                compiled_json = CAST(#{compiledJson,jdbcType=VARCHAR} AS JSON)
            WHERE id = #{id} AND status IN ('DRAFT', 'VALIDATED') AND checksum = #{checksum}
            """)
    int saveValidation(
            @Param("id") long id,
            @Param("checksum") String checksum,
            @Param("status") String status,
            @Param("validationStatus") String validationStatus,
            @Param("resultJson") String resultJson,
            @Param("compiledJson") String compiledJson);

    @Update("""
            UPDATE mock_flow_definition_version
            SET approval_request_id = #{requestId}
            WHERE id = #{id} AND status = 'VALIDATED' AND validation_status = 'VALID'
              AND checksum = #{checksum} AND approval_request_id IS NULL
            """)
    int markPendingApproval(
            @Param("id") long id,
            @Param("requestId") long requestId,
            @Param("checksum") String checksum);

    @Update("""
            UPDATE mock_flow_definition_version
            SET status = 'APPROVED', approved_at = #{approvedAt}
            WHERE id = #{id} AND status = 'VALIDATED' AND validation_status = 'VALID'
              AND approval_request_id = #{requestId} AND checksum = #{checksum}
            """)
    int markApproved(
            @Param("id") long id,
            @Param("requestId") long requestId,
            @Param("checksum") String checksum,
            @Param("approvedAt") Instant approvedAt);

    @Update("""
            UPDATE mock_flow_definition_version
            SET approval_request_id = NULL, approved_at = NULL
            WHERE id = #{id} AND status = 'VALIDATED'
              AND approval_request_id = #{requestId} AND checksum = #{checksum}
            """)
    int markRejected(
            @Param("id") long id,
            @Param("requestId") long requestId,
            @Param("checksum") String checksum);

    @Update("""
            UPDATE mock_flow_definition_version SET status = 'PUBLISHED', published_at = #{publishedAt}
            WHERE id = #{id} AND status = 'APPROVED'
            """)
    int markPublished(@Param("id") long id, @Param("publishedAt") Instant publishedAt);
}
