package com.xuntian.mock.control.requestlog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface RequestLogMapper {

    String COLUMNS = """
            id, mock_request_id AS mockRequestId, trace_id AS traceId,
            environment, app_code AS appCode, tenant_code AS tenantCode,
            test_account_masked AS testAccountMasked,
            provider_code AS providerCode, api_code AS apiCode,
            scenario_id AS scenarioId, scenario_version_id AS scenarioVersionId,
            release_id AS releaseId, flow_key AS flowKey,
            business_no_hmac AS businessNoHmac, hmac_key_version AS hmacKeyVersion,
            http_method AS httpMethod, path, request_summary AS requestSummary,
            response_summary AS responseSummary, http_status AS httpStatus,
            match_result AS matchResult, duration_ms AS durationMs, error_code AS errorCode,
            expire_at AS expireAt, created_at AS createdAt
            """;

    String FILTERS = """
            <if test='filter.traceId != null'>AND trace_id = #{filter.traceId}</if>
            <if test='filter.providerCode != null'>AND provider_code = #{filter.providerCode}</if>
            <if test='filter.apiCode != null'>AND api_code = #{filter.apiCode}</if>
            <if test='filter.scenarioId != null'>AND scenario_id = #{filter.scenarioId}</if>
            <if test='filter.appCode != null'>AND app_code = #{filter.appCode}</if>
            <if test='filter.mockRequestId != null'>AND mock_request_id = #{filter.mockRequestId}</if>
            <if test='filter.businessNoHmac != null'>AND business_no_hmac = #{filter.businessNoHmac}</if>
            <if test='filter.hmacKeyVersion != null'>AND hmac_key_version = #{filter.hmacKeyVersion}</if>
            <if test='filter.createdFrom != null'>AND created_at &gt;= #{filter.createdFrom}</if>
            <if test='filter.createdTo != null'>AND created_at &lt; #{filter.createdTo}</if>
            """;

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM mock_request_log
            WHERE 1 = 1
            """ + FILTERS + """
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<RequestLogRecord> selectPage(
            @Param("filter") RequestLogFilter filter,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM mock_request_log
            WHERE 1 = 1
            """ + FILTERS + """
            </script>
            """)
    long count(@Param("filter") RequestLogFilter filter);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM mock_request_log
            WHERE id = #{id} AND created_at >= #{start} AND created_at < #{end}
            """)
    RequestLogRecord selectDetail(
            @Param("id") String id,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
