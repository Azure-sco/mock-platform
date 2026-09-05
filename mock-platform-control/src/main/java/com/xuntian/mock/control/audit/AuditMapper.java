package com.xuntian.mock.control.audit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuditMapper {

    String COLUMNS = """
            id, request_id AS requestId, `operator`, `action`, object_type AS objectType,
            object_id AS objectId, object_checksum AS objectChecksum,
            CAST(before_json_masked AS CHAR) AS beforeJsonMasked,
            CAST(after_json_masked AS CHAR) AS afterJsonMasked,
            result, reason, created_at AS createdAt
            """;

    String FILTERS = """
            <if test='filter.requestId != null'>AND request_id = #{filter.requestId}</if>
            <if test='filter.operator != null'>AND `operator` = #{filter.operator}</if>
            <if test='filter.action != null'>AND `action` = #{filter.action}</if>
            <if test='filter.objectType != null'>AND object_type = #{filter.objectType}</if>
            <if test='filter.objectId != null'>AND object_id = #{filter.objectId}</if>
            <if test='filter.createdFrom != null'>AND created_at &gt;= #{filter.createdFrom}</if>
            <if test='filter.createdTo != null'>AND created_at &lt; #{filter.createdTo}</if>
            """;

    @Insert("""
            INSERT INTO mock_audit_log (
                request_id, `operator`, `action`, object_type, object_id, object_checksum,
                before_json_masked, after_json_masked, result, reason
            ) VALUES (
                #{requestId}, #{operator}, #{action}, #{objectType}, #{objectId}, #{objectChecksum},
                CAST(#{beforeJson,jdbcType=VARCHAR} AS JSON),
                CAST(#{afterJson,jdbcType=VARCHAR} AS JSON),
                'SUCCESS', NULL
            )
            """)
    int insert(
            @Param("requestId") String requestId,
            @Param("operator") String operator,
            @Param("action") String action,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("objectChecksum") String objectChecksum,
            @Param("beforeJson") String beforeJson,
            @Param("afterJson") String afterJson);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
              FROM mock_audit_log
             WHERE 1 = 1
            """ + FILTERS + """
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditRecord> selectPage(
            @Param("filter") AuditFilter filter,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM mock_audit_log WHERE 1 = 1
            """ + FILTERS + """
            </script>
            """)
    long count(@Param("filter") AuditFilter filter);
}
