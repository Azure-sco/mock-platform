package com.xuntian.mock.client.config;

import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalConfigProviderTest {

    @Test
    void replacesTheWholeSnapshotAndNotifiesListeners() {
        RoutingSnapshot initial = snapshot(1, RouteConfig.real());
        RoutingSnapshot next = snapshot(2, RouteConfig.mock(com.xuntian.mock.client.core.model.UnavailablePolicy.FAST_FAIL));
        LocalConfigProvider provider = new LocalConfigProvider(initial);
        List<Long> observedVersions = new ArrayList<Long>();
        provider.registerListener((previous, current) -> observedVersions.add(current.configVersion()));

        provider.update(next);

        assertThat(provider.current()).isSameAs(next);
        assertThat(observedVersions).containsExactly(2L);
    }

    @Test
    void rejectsNonIncreasingVersionWithoutChangingCurrentSnapshot() {
        RoutingSnapshot initial = snapshot(2, RouteConfig.real());
        LocalConfigProvider provider = new LocalConfigProvider(initial);

        assertThatThrownBy(() -> provider.update(snapshot(2, RouteConfig.real())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configVersion");
        assertThat(provider.current()).isSameAs(initial);
    }

    private RoutingSnapshot snapshot(long version, RouteConfig route) {
        return RoutingSnapshot.builder()
                .configVersion(version)
                .appCode("sample-app")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://localhost:9080"))
                .defaultRoute(route)
                .build();
    }
}
