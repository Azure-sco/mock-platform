package com.xuntian.mock.client.boot2;

import com.xuntian.mock.client.annotation.ThirdPartyMock;
import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.core.context.MockContextHolder;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThirdPartyMockAspectTest {

    @Test
    void createsOneRequestIdPerLogicalCallAndAlwaysCleansContext() {
        AnnotatedService proxy = proxy();

        String first = proxy.captureTwice();
        String second = proxy.captureTwice();

        String[] firstIds = first.split("\\|");
        assertThat(firstIds[0]).isEqualTo(firstIds[1]).startsWith("mr-");
        assertThat(second).doesNotStartWith(firstIds[0] + "|");
        assertThat(MockContextHolder.current()).isEmpty();

        assertThatThrownBy(proxy::fail).isInstanceOf(IllegalStateException.class);
        assertThat(MockContextHolder.current()).isEmpty();
    }

    private AnnotatedService proxy() {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("pomp-power")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://runtime:19091"))
                .defaultRoute(RouteConfig.real())
                .build();
        MockClientProperties properties = new MockClientProperties();
        properties.setTenant("tenant-a");
        properties.setTestAccount("tester-01");
        properties.setBusinessNo("SETTLE-42");
        AspectJProxyFactory factory = new AspectJProxyFactory(new AnnotatedService());
        factory.addAspect(new ThirdPartyMockAspect(new LocalConfigProvider(snapshot), properties));
        return factory.getProxy();
    }

    static class AnnotatedService {
        @ThirdPartyMock(provider = "OA", api = "OA_SETTLE_CREATE")
        public String captureTwice() {
            String id = MockContextHolder.current().get().mockRequestId();
            return id + "|" + MockContextHolder.current().get().mockRequestId();
        }

        @ThirdPartyMock(provider = "OA", api = "OA_SETTLE_CREATE")
        public void fail() {
            throw new IllegalStateException("boom");
        }
    }
}
