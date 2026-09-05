package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Optional;

@Component
@Profile("!local & !test")
public final class JdbcReleaseRecoveryAdapter implements ReleaseRecoveryPort {

    private static final String SELECT_ACTIVE_RELEASE = """
            SELECT ar.release_id, ar.activation_version, r.snapshot_bytes,
                   r.checksum, r.signature_key_id
              FROM mock_active_release ar
              JOIN mock_release r ON r.id = ar.release_id
             WHERE ar.environment = ?
               AND ar.app_code = ?
               AND ar.state IN ('ACTIVATING', 'APPLIED', 'PARTIAL')
               AND r.status IN ('READY', 'PUBLISHED')
            """;

    private static final String SELECT_FIXED_RELEASE = """
            SELECT r.id AS release_id,
                   COALESCE(MAX(ra.to_activation_version), 1) AS activation_version,
                   r.snapshot_bytes, r.checksum, r.signature_key_id
              FROM mock_release r
              LEFT JOIN mock_release_activation ra
                ON ra.to_release_id = r.id
               AND ra.environment = r.environment
               AND ra.app_code = r.app_code
             WHERE r.id = ? AND r.environment = ? AND r.app_code = ?
               AND r.status IN ('PUBLISHED', 'PARTIAL')
             GROUP BY r.id, r.snapshot_bytes, r.checksum, r.signature_key_id
            """;

    private final JdbcTemplate jdbc;
    private final Scheduler jdbcScheduler;
    private final RuntimeProperties properties;

    public JdbcReleaseRecoveryAdapter(
            JdbcTemplate jdbc,
            @Qualifier("runtimeJdbcScheduler") Scheduler runtimeJdbcScheduler,
            RuntimeProperties properties) {
        this.jdbc = jdbc;
        this.jdbcScheduler = runtimeJdbcScheduler;
        this.properties = properties;
    }

    @Override
    public Optional<ReleaseCandidate> recover(ReleaseScope scope) {
        return Mono.fromCallable(() -> jdbc.query(
                        SELECT_ACTIVE_RELEASE,
                        resultSet -> {
                            if (!resultSet.next()) {
                                return Optional.<ReleaseCandidate>empty();
                            }
                            ActiveReleasePointer pointer = new ActiveReleasePointer(
                                    resultSet.getString("release_id"),
                                    resultSet.getLong("activation_version"),
                                    resultSet.getString("checksum"),
                                    resultSet.getString("signature_key_id"));
                            return Optional.of(new ReleaseCandidate(
                                    pointer,
                                    resultSet.getBytes("snapshot_bytes")));
                        },
                        scope.environment(),
                        scope.app()))
                .subscribeOn(jdbcScheduler)
                .block(properties.getReleaseSourceTimeout());
    }

    @Override
    public Optional<ReleaseCandidate> recoverRelease(ReleaseScope scope, String releaseId) {
        return Mono.fromCallable(() -> jdbc.query(
                        SELECT_FIXED_RELEASE,
                        resultSet -> {
                            if (!resultSet.next()) return Optional.<ReleaseCandidate>empty();
                            ActiveReleasePointer pointer = new ActiveReleasePointer(
                                    resultSet.getString("release_id"),
                                    resultSet.getLong("activation_version"),
                                    resultSet.getString("checksum"),
                                    resultSet.getString("signature_key_id"));
                            return Optional.of(new ReleaseCandidate(pointer, resultSet.getBytes("snapshot_bytes")));
                        },
                        releaseId, scope.environment(), scope.app()))
                .subscribeOn(jdbcScheduler)
                .block(properties.getReleaseSourceTimeout());
    }
}
