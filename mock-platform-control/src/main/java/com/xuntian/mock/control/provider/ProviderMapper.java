package com.xuntian.mock.control.provider;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProviderMapper {

    String COLUMNS = """
            id, provider_code AS providerCode, provider_name AS providerName, owner, status,
            created_by AS createdBy, created_at AS createdAt,
            updated_by AS updatedBy, updated_at AS updatedAt
            """;

    @Select("SELECT " + COLUMNS + " FROM mock_provider ORDER BY provider_code")
    List<ProviderRecord> selectAll();

    @Select("SELECT " + COLUMNS + " FROM mock_provider WHERE id = #{id}")
    ProviderRecord selectById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM mock_provider WHERE provider_code = #{providerCode}")
    ProviderRecord selectByCode(@Param("providerCode") String providerCode);

    @Insert("""
            INSERT INTO mock_provider (
                provider_code, provider_name, owner, status, created_by, updated_by
            ) VALUES (
                #{providerCode}, #{providerName}, #{owner}, #{status}, #{operator}, #{operator}
            )
            """)
    int insert(
            @Param("providerCode") String providerCode,
            @Param("providerName") String providerName,
            @Param("owner") String owner,
            @Param("status") String status,
            @Param("operator") String operator);

    @Update("""
            UPDATE mock_provider
            SET provider_name = #{providerName}, owner = #{owner}, status = #{status},
                updated_by = #{operator}, updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id}
            """)
    int update(
            @Param("id") long id,
            @Param("providerName") String providerName,
            @Param("owner") String owner,
            @Param("status") String status,
            @Param("operator") String operator);
}
