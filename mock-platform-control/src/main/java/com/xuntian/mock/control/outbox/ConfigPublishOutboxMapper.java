package com.xuntian.mock.control.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface ConfigPublishOutboxMapper {

    @Insert("""
            INSERT INTO mock_config_publish_outbox (
                aggregate_type, aggregate_id, target_type, target_namespace,
                payload_encrypted, checksum, status, attempt_count, next_attempt_at
            ) VALUES (
                #{aggregateType}, #{aggregateId}, #{targetType}, #{targetNamespace},
                #{payloadEncrypted}, #{checksum}, 'NEW', 0, #{now}
            )
            """)
    int insert(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("targetType") String targetType,
            @Param("targetNamespace") String targetNamespace,
            @Param("payloadEncrypted") String payloadEncrypted,
            @Param("checksum") String checksum,
            @Param("now") Instant now);

    @Select("""
            SELECT id, aggregate_type AS aggregateType, aggregate_id AS aggregateId,
                   target_type AS targetType, target_namespace AS targetNamespace,
                   payload_encrypted AS payloadEncrypted, checksum, status,
                   attempt_count AS attemptCount, next_attempt_at AS nextAttemptAt,
                   last_error_masked AS lastErrorMasked, lease_owner AS leaseOwner,
                   lease_until AS leaseUntil, fencing_token AS fencingToken,
                   created_at AS createdAt, published_at AS publishedAt
            FROM mock_config_publish_outbox
            WHERE status IN ('NEW', 'FAILED') AND attempt_count < #{maxAttempts}
              AND next_attempt_at IS NOT NULL AND next_attempt_at <= #{now}
              AND (lease_until IS NULL OR lease_until < #{now})
            ORDER BY next_attempt_at, id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    ConfigPublishOutboxRecord lockNextClaimable(
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE mock_config_publish_outbox
            SET lease_owner = #{leaseOwner}, lease_until = #{leaseUntil},
                fencing_token = fencing_token + 1, attempt_count = attempt_count + 1
            WHERE id = #{id} AND status IN ('NEW', 'FAILED')
              AND fencing_token = #{expectedFencingToken}
              AND (lease_until IS NULL OR lease_until < #{now})
            """)
    int claim(
            @Param("id") long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("expectedFencingToken") long expectedFencingToken,
            @Param("now") Instant now);

    @Select("""
            SELECT id, aggregate_type AS aggregateType, aggregate_id AS aggregateId,
                   target_type AS targetType, target_namespace AS targetNamespace,
                   payload_encrypted AS payloadEncrypted, checksum, status,
                   attempt_count AS attemptCount, next_attempt_at AS nextAttemptAt,
                   last_error_masked AS lastErrorMasked, lease_owner AS leaseOwner,
                   lease_until AS leaseUntil, fencing_token AS fencingToken,
                   created_at AS createdAt, published_at AS publishedAt
            FROM mock_config_publish_outbox WHERE id = #{id}
            """)
    ConfigPublishOutboxRecord selectById(long id);

    @Select("""
            SELECT id, aggregate_type AS aggregateType, aggregate_id AS aggregateId,
                   target_type AS targetType, target_namespace AS targetNamespace,
                   payload_encrypted AS payloadEncrypted, checksum, status,
                   attempt_count AS attemptCount, next_attempt_at AS nextAttemptAt,
                   last_error_masked AS lastErrorMasked, lease_owner AS leaseOwner,
                   lease_until AS leaseUntil, fencing_token AS fencingToken,
                   created_at AS createdAt, published_at AS publishedAt
            FROM mock_config_publish_outbox WHERE id = #{id} FOR UPDATE
            """)
    ConfigPublishOutboxRecord lockById(long id);

    @Select("""
            SELECT id, aggregate_type AS aggregateType, aggregate_id AS aggregateId,
                   target_type AS targetType, target_namespace AS targetNamespace,
                   payload_encrypted AS payloadEncrypted, checksum, status,
                   attempt_count AS attemptCount, next_attempt_at AS nextAttemptAt,
                   last_error_masked AS lastErrorMasked, lease_owner AS leaseOwner,
                   lease_until AS leaseUntil, fencing_token AS fencingToken,
                   created_at AS createdAt, published_at AS publishedAt
            FROM mock_config_publish_outbox
            WHERE aggregate_type = #{aggregateType} AND aggregate_id = #{aggregateId}
            """)
    ConfigPublishOutboxRecord selectByAggregate(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId);

    @Update("""
            UPDATE mock_config_publish_outbox
            SET status = 'PUBLISHED', published_at = #{now}, next_attempt_at = NULL,
                last_error_masked = NULL, lease_owner = NULL, lease_until = NULL
            WHERE id = #{id} AND lease_owner = #{leaseOwner} AND fencing_token = #{fencingToken}
              AND status IN ('NEW', 'FAILED')
            """)
    int markPublished(
            @Param("id") long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("fencingToken") long fencingToken,
            @Param("now") Instant now);

    @Update("""
            UPDATE mock_config_publish_outbox
            SET status = 'FAILED', next_attempt_at = #{nextAttemptAt},
                last_error_masked = #{errorMasked}, lease_owner = NULL, lease_until = NULL
            WHERE id = #{id} AND lease_owner = #{leaseOwner} AND fencing_token = #{fencingToken}
              AND status IN ('NEW', 'FAILED')
            """)
    int markFailed(
            @Param("id") long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("fencingToken") long fencingToken,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorMasked") String errorMasked);

    @Update("""
            UPDATE mock_config_publish_outbox
            SET status = 'NEW', attempt_count = 0, next_attempt_at = #{now},
                last_error_masked = NULL, lease_owner = NULL, lease_until = NULL
            WHERE aggregate_type = 'SDK_CONFIG_ACTIVATION' AND aggregate_id = #{activationId}
              AND status = 'PUBLISHED' AND lease_owner IS NULL
            """)
    int requeuePublishedSdkConfig(@Param("activationId") String activationId, @Param("now") Instant now);
}
