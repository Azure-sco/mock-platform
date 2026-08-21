package com.xuntian.mock.client.boot3;

import com.xuntian.mock.client.annotation.ThirdPartyMock;
import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.core.context.MockContextHolder;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ThirdPartyMockAspectTest {

    @Test
    void scopesOneRequestIdToOneAnnotatedInvocation() {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("sample-jdk17")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://runtime:19091"))
                .defaultRoute(RouteConfig.real())
                .build();
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new AnnotatedService());
        proxyFactory.addAspect(new ThirdPartyMockAspect(
                new LocalConfigProvider(snapshot), new MockClientProperties()));
        AnnotatedService proxy = proxyFactory.getProxy();

        String result = proxy.captureTwice();

        String[] ids = result.split("\\|");
        assertThat(ids[0]).isEqualTo(ids[1]).startsWith("mr-");
        assertThat(MockContextHolder.current()).isEmpty();
    }

    static class AnnotatedService {
        @ThirdPartyMock(provider = "OA", api = "OA_SETTLE_CREATE")
        public String captureTwice() {
            String id = MockContextHolder.current().orElseThrow().mockRequestId();
            return id + "|" + MockContextHolder.current().orElseThrow().mockRequestId();
        }
    }
}
