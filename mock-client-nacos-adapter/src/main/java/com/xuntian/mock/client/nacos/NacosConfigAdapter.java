package com.xuntian.mock.client.nacos;

import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.config.MockConfigListener;
import com.xuntian.mock.client.config.MockConfigProvider;
import com.xuntian.mock.client.config.ConfigApplyResult;
import com.xuntian.mock.client.config.SignedConfigActivationProvider;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class NacosConfigAdapter implements MockConfigProvider {

    private final LocalConfigProvider snapshots;
    private final SignedConfigActivationProvider signedProvider;

    /** Local/test bridge retained for M0 compatibility; production wiring must use signed mode. */
    @Deprecated
    public NacosConfigAdapter(RoutingSnapshot initial) {
        this.snapshots = new LocalConfigProvider(requireLocalSnapshot(initial));
        this.signedProvider = null;
    }

    public NacosConfigAdapter(SignedConfigActivationProvider signedProvider) {
        this.snapshots = null;
        this.signedProvider = Objects.requireNonNull(signedProvider, "signedProvider");
    }

    /** Local/test bridge retained for M0 compatibility. */
    @Deprecated
    public void onValidatedSnapshot(RoutingSnapshot snapshot) {
        if (snapshots == null) {
            throw new IllegalStateException("validated snapshot bridge is unavailable in signed mode");
        }
        requireLocalSnapshot(snapshot);
        if (!snapshots.current().appCode().equals(snapshot.appCode())
                || !snapshots.current().environment().equals(snapshot.environment())) {
            throw new IllegalArgumentException("local snapshot scope cannot change");
        }
        snapshots.update(snapshot);
    }

    public ConfigApplyResult onConfigChanged(String signedActivationWrapper) {
        if (signedProvider == null) {
            throw new IllegalStateException("signed activation consumer is not configured");
        }
        return signedProvider.onConfigChanged(
                Objects.requireNonNull(signedActivationWrapper, "signedActivationWrapper")
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public RoutingSnapshot current() {
        return signedProvider == null ? snapshots.current() : signedProvider.current();
    }

    @Override
    public void registerListener(MockConfigListener listener) {
        if (signedProvider == null) {
            snapshots.registerListener(listener);
        } else {
            signedProvider.registerListener(listener);
        }
    }

    private static RoutingSnapshot requireLocalSnapshot(RoutingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if ("PROD".equalsIgnoreCase(snapshot.environment())
                || "PRODUCTION".equalsIgnoreCase(snapshot.environment())) {
            throw new IllegalArgumentException("production requires signed configuration mode");
        }
        return snapshot;
    }
}
