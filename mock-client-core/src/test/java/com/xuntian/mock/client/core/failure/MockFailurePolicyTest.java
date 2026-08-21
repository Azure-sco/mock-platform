package com.xuntian.mock.client.core.failure;

import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.model.UnavailablePolicy;
import com.xuntian.mock.client.core.routing.FallbackResponse;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RouteDecision;
import com.xuntian.mock.client.core.routing.RouteResolver;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class MockFailurePolicyTest {

    @Test
    void fallsBackToRealOnlyForApprovedConnectionFailure() {
        RouteDecision decision = decision(RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_REAL)
                .allowRealHost("real.example.com")
                .build());

        assertThat(MockFailurePolicy.decide(
                context(), decision, URI.create("https://real.example.com/api"), true,
                new ConnectException("refused")))
                .isEqualTo(FailureAction.FALLBACK_REAL);
        assertThat(MockFailurePolicy.decide(
                context(), decision, URI.create("https://real.example.com/api"), true,
                new SocketTimeoutException("read timed out")))
                .isEqualTo(FailureAction.FAST_FAIL);
    }

    @Test
    void usesConfiguredHttpFallbackResponseWithoutCallingReal() {
        RouteDecision decision = decision(RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_RESPONSE)
                .fallbackResponse(new FallbackResponse(503, "application/json", "{\"code\":\"DOWN\"}"))
                .build());

        assertThat(MockFailurePolicy.decide(
                context(), decision, URI.create("https://real.example.com/api"), true,
                new ConnectException("refused")))
                .isEqualTo(FailureAction.FALLBACK_RESPONSE);
    }

    @Test
    void fallsBackToFastFailWhenFallbackResponseIsMissing() {
        RouteDecision decision = decision(RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_RESPONSE)
                .build());

        assertThat(MockFailurePolicy.decide(
                context(), decision, URI.create("https://real.example.com/api"), true,
                new ConnectException("refused")))
                .isEqualTo(FailureAction.FAST_FAIL);
    }

    private RouteDecision decision(RouteConfig routeConfig) {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("app")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://localhost:19091"))
                .defaultRoute(routeConfig)
                .build();
        return new RouteResolver().resolve(snapshot, context());
    }

    private MockContext context() {
        return MockContext.builder()
                .appCode("app")
                .environment("TEST")
                .provider("OA")
                .api("CREATE")
                .mockRequestId("mr-1")
                .build();
    }
}
