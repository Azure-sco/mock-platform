package com.xuntian.mock.client.nacos;

import com.xuntian.mock.client.config.ConfigApplyResult;
import com.xuntian.mock.client.config.SignedConfigActivationProvider;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NacosConfigAdapterTest {

    @Test
    void appliesAValidatedSnapshotAsOneAtomicUpdate() {
        NacosConfigAdapter adapter = new NacosConfigAdapter(snapshot(1));

        RoutingSnapshot next = snapshot(2);
        adapter.onValidatedSnapshot(next);

        assertThat(adapter.current()).isSameAs(next);
    }

    @Test
    void signedModeDelegatesRawNacosValueAndDisablesTheLegacyBypass() {
        RoutingSnapshot initial = snapshot(1);
        SignedConfigActivationProvider provider = new SignedConfigActivationProvider(
                "new-app",
                "TEST",
                "nacos-sdk-1",
                initial,
                keyId -> null,
                acknowledgement -> { },
                Clock.systemUTC());
        NacosConfigAdapter adapter = new NacosConfigAdapter(provider);

        ConfigApplyResult result = adapter.onConfigChanged("{}");

        assertThat(result.status()).isEqualTo(ConfigApplyResult.Status.REJECTED);
        assertThat(adapter.current()).isSameAs(initial);
        assertThatThrownBy(() -> adapter.onValidatedSnapshot(snapshot(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    private RoutingSnapshot snapshot(long version) {
        return RoutingSnapshot.builder()
                .configVersion(version)
                .appCode("new-app")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://localhost:9080"))
                .defaultRoute(RouteConfig.real())
                .build();
    }
}
