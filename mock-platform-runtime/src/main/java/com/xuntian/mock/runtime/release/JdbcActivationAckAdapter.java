package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.sql.Timestamp;

@Component
@Profile("!local & !test")
public final class JdbcActivationAckAdapter implements ActivationAckPort {

    private static final String UPSERT_ACK = """
            INSERT INTO mock_runtime_activation_ack (
                environment, app_code, runtime_node_id, release_id,
                activation_version, status, error_masked, reported_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                release_id = VALUES(release_id),
                status = VALUES(status),
                error_masked = VALUES(error_masked),
                reported_at = VALUES(reported_at)
            """;

    private final JdbcTemplate jdbc;
    private final Scheduler jdbcScheduler;
    private final RuntimeProperties properties;

    public JdbcActivationAckAdapter(
            JdbcTemplate jdbc,
            @Qualifier("runtimeJdbcScheduler") Scheduler runtimeJdbcScheduler,
            RuntimeProperties properties) {
        this.jdbc = jdbc;
        this.jdbcScheduler = runtimeJdbcScheduler;
        this.properties = properties;
    }

    @Override
    public void record(ActivationAck ack) {
        Mono.fromRunnable(() -> jdbc.update(
                        UPSERT_ACK,
                        ack.scope().environment(),
                        ack.scope().app(),
                        ack.runtimeNodeId(),
                        ack.releaseId(),
                        ack.activationVersion(),
                        ack.status().name(),
                        ack.errorMasked(),
                        Timestamp.from(ack.reportedAt())))
                .subscribeOn(jdbcScheduler)
                .block(properties.getReleaseSourceTimeout());
    }
}
