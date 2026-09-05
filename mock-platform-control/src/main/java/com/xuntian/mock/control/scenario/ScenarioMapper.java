package com.xuntian.mock.control.scenario;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ScenarioMapper {

    String SCENARIO_COLUMNS = """
            id, scenario_code AS scenarioCode, scenario_name AS scenarioName,
            provider_id AS providerId, api_id AS apiId,
            current_draft_version AS currentDraftVersion, status,
            created_by AS createdBy, created_at AS createdAt,
            updated_by AS updatedBy, updated_at AS updatedAt
            """;

    String VERSION_COLUMNS = """
            id, scenario_id AS scenarioId, version_no AS versionNo, status,
            contract_version_id AS contractVersionId,
            flow_definition_version_id AS flowDefinitionVersionId, priority,
            effective_from AS effectiveFrom, effective_to AS effectiveTo,
            CAST(scope_json AS CHAR) AS scopeJson,
            CAST(match_rule_json AS CHAR) AS matchRuleJson,
            CAST(response_json AS CHAR) AS responseJson,
            CAST(callback_json AS CHAR) AS callbackJson,
            CAST(compiled_json AS CHAR) AS compiledJson,
            checksum, validation_status AS validationStatus,
            CAST(validation_result_json AS CHAR) AS validationResultJson,
            approval_request_id AS approvalRequestId, approved_at AS approvedAt,
            published_at AS publishedAt, disabled_at AS disabledAt,
            created_by AS createdBy, created_at AS createdAt
            """;

    @Select("SELECT " + SCENARIO_COLUMNS + " FROM mock_scenario ORDER BY scenario_code")
    List<ScenarioRecord> selectAll();

    @Select("SELECT " + SCENARIO_COLUMNS + " FROM mock_scenario WHERE id = #{id}")
    ScenarioRecord selectById(@Param("id") long id);

    @Select("SELECT " + SCENARIO_COLUMNS + " FROM mock_scenario WHERE scenario_code = #{code}")
    ScenarioRecord selectByCode(@Param("code") String code);

    @Select("SELECT " + SCENARIO_COLUMNS + " FROM mock_scenario WHERE id = #{id} FOR UPDATE")
    ScenarioRecord lockById(@Param("id") long id);

    @Insert("""
            INSERT INTO mock_scenario (
                scenario_code, scenario_name, provider_id, api_id, status,
                created_by, updated_by
            ) VALUES (
                #{code}, #{name}, #{providerId}, #{apiId}, 'ENABLED', #{operator}, #{operator}
            )
            """)
    int insertScenario(
            @Param("code") String code,
            @Param("name") String name,
            @Param("providerId") long providerId,
            @Param("apiId") long apiId,
            @Param("operator") String operator);

    @Update("""
            UPDATE mock_scenario SET scenario_name = #{name}, status = #{status},
                updated_by = #{operator}, updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id}
            """)
    int updateScenario(
            @Param("id") long id,
            @Param("name") String name,
            @Param("status") String status,
            @Param("operator") String operator);

    @Update("""
            UPDATE mock_scenario SET status = 'DISABLED', updated_by = #{operator},
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id} AND status = 'ENABLED'
            """)
    int disableScenario(@Param("id") long id, @Param("operator") String operator);

    @Update("""
            UPDATE mock_scenario SET current_draft_version = #{versionNo},
                updated_by = #{operator}, updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id}
            """)
    int updateCurrentDraft(
            @Param("id") long id,
            @Param("versionNo") int versionNo,
            @Param("operator") String operator);

    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM mock_scenario_version WHERE scenario_id = #{scenarioId}")
    int nextVersionNo(@Param("scenarioId") long scenarioId);

    @Insert("""
            INSERT INTO mock_scenario_version (
                scenario_id, version_no, status, contract_version_id,
                flow_definition_version_id, priority, effective_from, effective_to,
                scope_json, match_rule_json, response_json, callback_json,
                checksum, validation_status, created_by
            ) VALUES (
                #{scenarioId}, #{versionNo}, 'DRAFT', #{contractVersionId},
                #{flowDefinitionVersionId,jdbcType=BIGINT}, #{priority},
                #{effectiveFrom,jdbcType=TIMESTAMP}, #{effectiveTo,jdbcType=TIMESTAMP},
                CAST(#{scopeJson} AS JSON), CAST(#{matchRuleJson} AS JSON),
                CAST(#{responseJson} AS JSON), CAST(#{callbackJson} AS JSON),
                #{checksum}, 'NOT_VALIDATED', #{operator}
            )
            """)
    int insertVersion(
            @Param("scenarioId") long scenarioId,
            @Param("versionNo") int versionNo,
            @Param("contractVersionId") long contractVersionId,
            @Param("flowDefinitionVersionId") Long flowDefinitionVersionId,
            @Param("priority") int priority,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("effectiveTo") Instant effectiveTo,
            @Param("scopeJson") String scopeJson,
            @Param("matchRuleJson") String matchRuleJson,
            @Param("responseJson") String responseJson,
            @Param("callbackJson") String callbackJson,
            @Param("checksum") String checksum,
            @Param("operator") String operator);

    @Select("SELECT " + VERSION_COLUMNS + " FROM mock_scenario_version WHERE id = #{id}")
    ScenarioVersionRecord selectVersionById(@Param("id") long id);

    @Select("SELECT " + VERSION_COLUMNS + " FROM mock_scenario_version WHERE id = #{id} FOR UPDATE")
    ScenarioVersionRecord lockVersionById(@Param("id") long id);

    @Select("""
            SELECT 
            """ + VERSION_COLUMNS + """
            FROM mock_scenario_version WHERE scenario_id = #{scenarioId}
            ORDER BY version_no DESC
            """)
    List<ScenarioVersionRecord> selectVersions(@Param("scenarioId") long scenarioId);

    @Select("""
            SELECT 
            """ + VERSION_COLUMNS + """
            FROM mock_scenario_version
            WHERE scenario_id = #{scenarioId} AND version_no = #{versionNo}
            """)
    ScenarioVersionRecord selectVersionByNumber(
            @Param("scenarioId") long scenarioId,
            @Param("versionNo") int versionNo);

    @Select("""
            SELECT sv.id, sv.scenario_id AS scenarioId, sv.version_no AS versionNo, sv.status,
                sv.contract_version_id AS contractVersionId,
                sv.flow_definition_version_id AS flowDefinitionVersionId, sv.priority,
                sv.effective_from AS effectiveFrom, sv.effective_to AS effectiveTo,
                CAST(sv.scope_json AS CHAR) AS scopeJson,
                CAST(sv.match_rule_json AS CHAR) AS matchRuleJson,
                CAST(sv.response_json AS CHAR) AS responseJson,
                CAST(sv.callback_json AS CHAR) AS callbackJson,
                CAST(sv.compiled_json AS CHAR) AS compiledJson,
                sv.checksum, sv.validation_status AS validationStatus,
                CAST(sv.validation_result_json AS CHAR) AS validationResultJson,
                sv.approval_request_id AS approvalRequestId, sv.approved_at AS approvedAt,
                sv.published_at AS publishedAt, sv.disabled_at AS disabledAt,
                sv.created_by AS createdBy, sv.created_at AS createdAt
            FROM mock_scenario_version sv
            JOIN mock_scenario s ON s.id = sv.scenario_id
            WHERE s.api_id = #{apiId} AND sv.id <> #{excludeId}
              AND sv.status IN ('VALIDATED', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED')
            """)
    List<ScenarioVersionRecord> selectConflictCandidates(
            @Param("apiId") long apiId,
            @Param("excludeId") long excludeId);

    @Update("""
            UPDATE mock_scenario_version
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
            UPDATE mock_scenario_version
            SET status = 'PENDING_APPROVAL', approval_request_id = #{requestId}
            WHERE id = #{id} AND status = 'VALIDATED' AND validation_status = 'VALID'
              AND checksum = #{checksum} AND approval_request_id IS NULL
            """)
    int markPendingApproval(
            @Param("id") long id,
            @Param("requestId") long requestId,
            @Param("checksum") String checksum);

    @Update("""
            UPDATE mock_scenario_version
            SET status = 'APPROVED', approved_at = #{approvedAt}
            WHERE id = #{id} AND status = 'PENDING_APPROVAL'
              AND approval_request_id = #{requestId} AND checksum = #{checksum}
            """)
    int markApproved(
            @Param("id") long id,
            @Param("requestId") long requestId,
            @Param("checksum") String checksum,
            @Param("approvedAt") Instant approvedAt);

    @Update("""
            UPDATE mock_scenario_version
            SET status = 'VALIDATED', approval_request_id = NULL, approved_at = NULL
            WHERE id = #{id} AND status = 'PENDING_APPROVAL'
              AND approval_request_id = #{requestId} AND checksum = #{checksum}
            """)
    int markRejected(
            @Param("id") long id,
            @Param("requestId") long requestId,
            @Param("checksum") String checksum);

    @Update("""
            UPDATE mock_scenario_version SET status = 'PUBLISHED', published_at = #{publishedAt}
            WHERE id = #{id} AND status = 'APPROVED'
            """)
    int markPublished(@Param("id") long id, @Param("publishedAt") Instant publishedAt);
}
