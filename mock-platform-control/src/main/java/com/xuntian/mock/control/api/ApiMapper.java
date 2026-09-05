package com.xuntian.mock.control.api;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ApiMapper {

    String COLUMNS = """
            id, provider_id AS providerId, api_code AS apiCode, api_name AS apiName,
            http_method AS httpMethod, path, content_type AS contentType, owner, status,
            created_by AS createdBy, created_at AS createdAt,
            updated_by AS updatedBy, updated_at AS updatedAt
            """;

    @Select("SELECT " + COLUMNS + " FROM mock_api WHERE provider_id = #{providerId} ORDER BY api_code")
    List<ApiRecord> selectByProvider(@Param("providerId") long providerId);

    @Select("SELECT " + COLUMNS + " FROM mock_api WHERE id = #{id}")
    ApiRecord selectById(@Param("id") long id);

    @Select("SELECT id FROM mock_api WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") long id);

    @Select("""
            SELECT 
            """ + COLUMNS + """
            FROM mock_api WHERE provider_id = #{providerId} AND api_code = #{apiCode}
            """)
    ApiRecord selectByProviderAndCode(
            @Param("providerId") long providerId,
            @Param("apiCode") String apiCode);

    @Insert("""
            INSERT INTO mock_api (
                provider_id, api_code, api_name, http_method, path, content_type,
                owner, status, created_by, updated_by
            ) VALUES (
                #{providerId}, #{apiCode}, #{apiName}, #{httpMethod}, #{path}, #{contentType},
                #{owner}, #{status}, #{operator}, #{operator}
            )
            """)
    int insert(
            @Param("providerId") long providerId,
            @Param("apiCode") String apiCode,
            @Param("apiName") String apiName,
            @Param("httpMethod") String httpMethod,
            @Param("path") String path,
            @Param("contentType") String contentType,
            @Param("owner") String owner,
            @Param("status") String status,
            @Param("operator") String operator);

    @Update("""
            UPDATE mock_api
            SET api_name = #{apiName}, http_method = #{httpMethod}, path = #{path},
                content_type = #{contentType}, owner = #{owner}, status = #{status},
                updated_by = #{operator}, updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id}
            """)
    int update(
            @Param("id") long id,
            @Param("apiName") String apiName,
            @Param("httpMethod") String httpMethod,
            @Param("path") String path,
            @Param("contentType") String contentType,
            @Param("owner") String owner,
            @Param("status") String status,
            @Param("operator") String operator);
}
