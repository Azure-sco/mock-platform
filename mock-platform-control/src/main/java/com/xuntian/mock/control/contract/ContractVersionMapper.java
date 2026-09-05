package com.xuntian.mock.control.contract;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ContractVersionMapper {

    String COLUMNS = """
            id, api_id AS apiId, version_no AS versionNo, status,
            CAST(request_schema_json AS CHAR) AS requestSchemaJson,
            CAST(response_schema_json AS CHAR) AS responseSchemaJson,
            CAST(examples_json AS CHAR) AS examplesJson,
            CAST(error_codes_json AS CHAR) AS errorCodesJson,
            CAST(business_key_extractor_json AS CHAR) AS businessKeyExtractorJson,
            CAST(signature_metadata_json AS CHAR) AS signatureMetadataJson,
            source_type AS sourceType, source_file_hash AS sourceFileHash, checksum,
            created_by AS createdBy, created_at AS createdAt,
            published_by AS publishedBy, published_at AS publishedAt
            """;

    @Select("SELECT " + COLUMNS + " FROM mock_contract_version WHERE api_id = #{apiId} ORDER BY version_no DESC")
    List<ContractVersionRecord> selectByApi(@Param("apiId") long apiId);

    @Select("SELECT " + COLUMNS + " FROM mock_contract_version WHERE id = #{id}")
    ContractVersionRecord selectById(@Param("id") long id);

    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM mock_contract_version WHERE api_id = #{apiId}")
    int nextVersionNo(@Param("apiId") long apiId);

    @Insert("""
            INSERT INTO mock_contract_version (
                api_id, version_no, status, request_schema_json, response_schema_json,
                examples_json, error_codes_json, business_key_extractor_json,
                signature_metadata_json, source_type, source_file_hash, checksum, created_by
            ) VALUES (
                #{apiId}, #{versionNo}, 'DRAFT',
                CAST(#{requestSchemaJson} AS JSON), CAST(#{responseSchemaJson} AS JSON),
                CAST(#{examplesJson,jdbcType=VARCHAR} AS JSON),
                CAST(#{errorCodesJson,jdbcType=VARCHAR} AS JSON),
                CAST(#{businessKeyExtractorJson,jdbcType=VARCHAR} AS JSON),
                CAST(#{signatureMetadataJson,jdbcType=VARCHAR} AS JSON),
                #{sourceType}, #{sourceFileHash}, #{checksum}, #{operator}
            )
            """)
    int insert(
            @Param("apiId") long apiId,
            @Param("versionNo") int versionNo,
            @Param("requestSchemaJson") String requestSchemaJson,
            @Param("responseSchemaJson") String responseSchemaJson,
            @Param("examplesJson") String examplesJson,
            @Param("errorCodesJson") String errorCodesJson,
            @Param("businessKeyExtractorJson") String businessKeyExtractorJson,
            @Param("signatureMetadataJson") String signatureMetadataJson,
            @Param("sourceType") String sourceType,
            @Param("sourceFileHash") String sourceFileHash,
            @Param("checksum") String checksum,
            @Param("operator") String operator);

    @Select("""
            SELECT 
            """ + COLUMNS + """
            FROM mock_contract_version
            WHERE api_id = #{apiId} AND version_no = #{versionNo}
            """)
    ContractVersionRecord selectByApiAndVersion(
            @Param("apiId") long apiId,
            @Param("versionNo") int versionNo);

    @Update("""
            UPDATE mock_contract_version
            SET status = 'VALIDATED'
            WHERE id = #{id} AND status IN ('DRAFT', 'VALIDATED')
            """)
    int markValidated(@Param("id") long id);

    @Update("""
            UPDATE mock_contract_version
            SET status = 'PUBLISHED', published_by = #{operator}, published_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id} AND status = 'VALIDATED'
            """)
    int publish(@Param("id") long id, @Param("operator") String operator);
}
