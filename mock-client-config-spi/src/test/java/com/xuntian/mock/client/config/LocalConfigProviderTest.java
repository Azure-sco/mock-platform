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

    @Test
    void invalidationKeepsRoutingAndCanRecoverWithTheObservedVersion() {
        RoutingSnapshot initial = snapshot(2, RouteConfig.real());
        LocalConfigProvider provider = new LocalConfigProvider(initial);

        assertThat(provider.markInvalid(3)).isTrue();
        assertThat(provider.current().configVersion()).isEqualTo(2);
        assertThat(provider.current().observedConfigVersion()).isEqualTo(3);
        assertThat(provider.current().mockConfigValid()).isFalse();

        RoutingSnapshot recovered = snapshot(3, RouteConfig.real());
        provider.update(recovered);

        assertThat(provider.current()).isSameAs(recovered);
        assertThat(provider.current().mockConfigValid()).isTrue();
    }

    @Test
    void listenerFailureDoesNotUndoCommittedSnapshotOrSkipOtherListeners() {
        LocalConfigProvider provider = new LocalConfigProvider(snapshot(1, RouteConfig.real()));
        List<Long> observedVersions = new ArrayList<Long>();
        provider.registerListener((previous, current) -> {
            throw new IllegalStateException("listener failed");
        });
        provider.registerListener((previous, current) -> observedVersions.add(current.configVersion()));

        provider.update(snapshot(2, RouteConfig.real()));

        assertThat(provider.current().configVersion()).isEqualTo(2);
        assertThat(provider.listenerFailureCount()).isEqualTo(1);
        assertThat(observedVersions).containsExactly(2L);
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
