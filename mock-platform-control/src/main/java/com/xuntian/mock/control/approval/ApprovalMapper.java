package com.xuntian.mock.control.approval;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ApprovalMapper {

    String REQUEST_COLUMNS = """
            id, object_type AS objectType, object_id AS objectId,
            object_checksum AS objectChecksum, policy_code AS policyCode,
            required_count AS requiredCount, status, requested_by AS requestedBy,
            requested_at AS requestedAt, completed_at AS completedAt
            """;

    String DECISION_COLUMNS = """
            id, approval_request_id AS approvalRequestId, reviewer, decision, comment,
            decided_at AS decidedAt
            """;

    @Select("SELECT " + REQUEST_COLUMNS + " FROM mock_approval_request ORDER BY requested_at DESC, id DESC")
    List<ApprovalRequestRecord> selectAll();

    @Select("SELECT " + REQUEST_COLUMNS + " FROM mock_approval_request WHERE id = #{id}")
    ApprovalRequestRecord selectById(@Param("id") long id);

    @Select("SELECT " + REQUEST_COLUMNS + " FROM mock_approval_request WHERE id = #{id} FOR UPDATE")
    ApprovalRequestRecord lockById(@Param("id") long id);

    @Select("""
            SELECT 
            """ + REQUEST_COLUMNS + """
            FROM mock_approval_request
            WHERE object_type = #{objectType} AND object_id = #{objectId}
              AND object_checksum = #{checksum} AND policy_code = #{policyCode}
            """)
    ApprovalRequestRecord selectUnique(
            @Param("objectType") String objectType,
            @Param("objectId") long objectId,
            @Param("checksum") String checksum,
            @Param("policyCode") String policyCode);

    @Insert("""
            INSERT INTO mock_approval_request (
                object_type, object_id, object_checksum, policy_code,
                required_count, status, requested_by, requested_at
            ) VALUES (
                #{objectType}, #{objectId}, #{checksum}, #{policyCode},
                #{requiredCount}, 'PENDING', #{requestedBy}, #{requestedAt}
            )
            """)
    int insertRequest(
            @Param("objectType") String objectType,
            @Param("objectId") long objectId,
            @Param("checksum") String checksum,
            @Param("policyCode") String policyCode,
            @Param("requiredCount") int requiredCount,
            @Param("requestedBy") String requestedBy,
            @Param("requestedAt") Instant requestedAt);

    @Insert("""
            INSERT INTO mock_approval_decision (
                approval_request_id, reviewer, decision, comment, decided_at
            ) VALUES (#{requestId}, #{reviewer}, #{decision}, #{comment}, #{decidedAt})
            """)
    int insertDecision(
            @Param("requestId") long requestId,
            @Param("reviewer") String reviewer,
            @Param("decision") String decision,
            @Param("comment") String comment,
            @Param("decidedAt") Instant decidedAt);

    @Select("SELECT " + DECISION_COLUMNS + " FROM mock_approval_decision WHERE approval_request_id = #{requestId} ORDER BY decided_at, id")
    List<ApprovalDecisionRecord> selectDecisions(@Param("requestId") long requestId);

    @Select("""
            SELECT COUNT(*) FROM mock_approval_decision
            WHERE approval_request_id = #{requestId} AND decision = 'APPROVE'
            """)
    int countApprovals(@Param("requestId") long requestId);

    @Update("""
            UPDATE mock_approval_request SET status = #{status}, completed_at = #{completedAt}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int complete(
            @Param("id") long id,
            @Param("status") String status,
            @Param("completedAt") Instant completedAt);
}
