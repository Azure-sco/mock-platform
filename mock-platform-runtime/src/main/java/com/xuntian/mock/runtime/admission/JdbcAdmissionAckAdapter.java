package com.xuntian.mock.runtime.admission;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.sql.Timestamp;
import java.time.Instant;

@Component
@Profile("!local & !test")
public final class JdbcAdmissionAckAdapter implements AdmissionAckPort {

    private static final String UPSERT = """
            INSERT INTO mock_runtime_policy_ack (
                runtime_node_id, binding_id, environment, app_code, policy_type,
                policy_version_id, binding_version, status, error_masked, reported_at
            ) VALUES (?, ?, ?, ?, 'APP_ACL', ?, ?, 'READY', NULL, ?)
            ON DUPLICATE KEY UPDATE
                policy_version_id = VALUES(policy_version_id),
                status = 'READY', error_masked = NULL, reported_at = VALUES(reported_at)
            """;

    private final JdbcTemplate jdbc;
    private final Scheduler scheduler;
    private final RuntimeProperties properties;

    public JdbcAdmissionAckAdapter(
            JdbcTemplate jdbc,
            @Qualifier("runtimeJdbcScheduler") Scheduler scheduler,
            RuntimeProperties properties) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @Override
    public void ready(VerifiedAdmissionSnapshot snapshot, String runtimeNodeId, Instant reportedAt) {
        Mono.fromRunnable(() -> jdbc.update(
                        UPSERT,
                        runtimeNodeId,
                        snapshot.bindingId(),
                        snapshot.scope().environment(),
                        snapshot.scope().appCode(),
                        snapshot.policyVersionId(),
                        snapshot.bindingVersion(),
                        Timestamp.from(reportedAt)))
                .subscribeOn(scheduler)
                .block(properties.getReleaseSourceTimeout());
    }
}
