package com.xuntian.mock.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("xuntian.mock.runtime")
public class RuntimeProperties {

    private String environment = "TEST";
    private String fixtureLocation = "classpath:runtime-fixture.json";
    private int requestLogRetentionDays = 30;
    private Map<String, String> localAppTokens = new LinkedHashMap<>();
    private List<String> publishedApps = List.of();
    private String runtimeNodeId = "runtime-node-unconfigured";
    private Map<String, String> snapshotPublicKeys = new LinkedHashMap<>();
    private Duration releaseSourceTimeout = Duration.ofSeconds(2);
    private Duration releaseSnapshotRedisTtl = Duration.ofHours(24);
    private Duration lastKnownGoodWindow = Duration.ofMinutes(10);

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getFixtureLocation() {
        return fixtureLocation;
    }

    public void setFixtureLocation(String fixtureLocation) {
        this.fixtureLocation = fixtureLocation;
    }

    public int getRequestLogRetentionDays() {
        return requestLogRetentionDays;
    }

    public void setRequestLogRetentionDays(int requestLogRetentionDays) {
        if (requestLogRetentionDays < 1 || requestLogRetentionDays > 365) {
            throw new IllegalArgumentException("requestLogRetentionDays must be between 1 and 365");
        }
        this.requestLogRetentionDays = requestLogRetentionDays;
    }

    public Map<String, String> getLocalAppTokens() {
        return localAppTokens;
    }

    public void setLocalAppTokens(Map<String, String> localAppTokens) {
        this.localAppTokens = localAppTokens;
    }

    public List<String> getPublishedApps() {
        return publishedApps;
    }

    public void setPublishedApps(List<String> publishedApps) {
        if (publishedApps == null) {
            this.publishedApps = List.of();
            return;
        }
        this.publishedApps = publishedApps.stream()
                .filter(value -> value != null && !value.isBlank())
                .peek(value -> {
                    if (value.length() > 128 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
                        throw new IllegalArgumentException("publishedApps contains an invalid app code");
                    }
                })
                .distinct()
                .toList();
    }

    public String getRuntimeNodeId() {
        return runtimeNodeId;
    }

    public void setRuntimeNodeId(String runtimeNodeId) {
        if (runtimeNodeId == null || runtimeNodeId.length() > 128
                || !runtimeNodeId.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("runtimeNodeId is invalid");
        }
        this.runtimeNodeId = runtimeNodeId;
    }

    public Map<String, String> getSnapshotPublicKeys() {
        return snapshotPublicKeys;
    }

    public void setSnapshotPublicKeys(Map<String, String> snapshotPublicKeys) {
        this.snapshotPublicKeys = snapshotPublicKeys == null
                ? Map.of()
                : Map.copyOf(snapshotPublicKeys);
    }

    public Duration getReleaseSourceTimeout() {
        return releaseSourceTimeout;
    }

    public void setReleaseSourceTimeout(Duration releaseSourceTimeout) {
        if (releaseSourceTimeout == null || releaseSourceTimeout.isZero()
                || releaseSourceTimeout.isNegative() || releaseSourceTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("releaseSourceTimeout must be between 0 and 5 seconds");
        }
        this.releaseSourceTimeout = releaseSourceTimeout;
    }

    public Duration getReleaseSnapshotRedisTtl() {
        return releaseSnapshotRedisTtl;
    }

    public void setReleaseSnapshotRedisTtl(Duration releaseSnapshotRedisTtl) {
        if (releaseSnapshotRedisTtl == null || releaseSnapshotRedisTtl.isZero()
                || releaseSnapshotRedisTtl.isNegative()) {
            throw new IllegalArgumentException("releaseSnapshotRedisTtl must be positive");
        }
        this.releaseSnapshotRedisTtl = releaseSnapshotRedisTtl;
    }

    public Duration getLastKnownGoodWindow() {
        return lastKnownGoodWindow;
    }

    public void setLastKnownGoodWindow(Duration lastKnownGoodWindow) {
        if (lastKnownGoodWindow == null || lastKnownGoodWindow.isZero()
                || lastKnownGoodWindow.isNegative()
                || lastKnownGoodWindow.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("lastKnownGoodWindow must be between 0 and 10 minutes");
        }
        this.lastKnownGoodWindow = lastKnownGoodWindow;
    }
}
