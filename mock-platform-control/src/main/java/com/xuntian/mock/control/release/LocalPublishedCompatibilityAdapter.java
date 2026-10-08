package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("local & local-published & !test")
public final class LocalPublishedCompatibilityAdapter implements ReleaseCompatibilityPort {
    private final ReleaseMapper releases;
    private final CanonicalJsonCodec json;
    private final JdbcTemplate jdbc;

    public LocalPublishedCompatibilityAdapter(ReleaseMapper releases, CanonicalJsonCodec json, JdbcTemplate jdbc) {
        this.releases = releases;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Override
    public void requireCompatible(String environment, String app, String releaseId) {
        ReleaseRecord release = releases.selectRelease(releaseId);
        var snapshot = json.read(release.snapshotBytes()).path("snapshot");
        boolean callbacks = false;
        for (var scenario : snapshot.path("scenarios")) {
            callbacks |= !scenario.path("callbacks").isEmpty();
        }
        Long flows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_flow_instance WHERE environment=? AND app_code=?",
                Long.class, environment, app);
        if (!"TEST".equals(environment) || !snapshot.path("flowDefinitions").isEmpty() || callbacks
                || flows == null || flows > 0) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                    "Local publication supports TEST stateless scenarios only; Flow/Callback require full infrastructure adapters");
        }
    }
}
