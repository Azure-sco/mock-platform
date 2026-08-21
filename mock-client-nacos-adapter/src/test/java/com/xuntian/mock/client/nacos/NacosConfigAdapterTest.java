package com.xuntian.mock.client.nacos;

import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class NacosConfigAdapterTest {

    @Test
    void appliesAValidatedSnapshotAsOneAtomicUpdate() {
        NacosConfigAdapter adapter = new NacosConfigAdapter(snapshot(1));

        RoutingSnapshot next = snapshot(2);
        adapter.onValidatedSnapshot(next);

        assertThat(adapter.current()).isSameAs(next);
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
