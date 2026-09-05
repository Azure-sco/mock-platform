package com.xuntian.mock.client.core.routing;

import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.failure.MockConfigInvalidException;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.model.UnavailablePolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteResolverTest {

    private final RouteResolver resolver = new RouteResolver();

    @Test
    void apiRuleOverridesProviderAndDefaultRules() {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(7)
                .appCode("sample-app")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://localhost:9080"))
                .defaultRoute(RouteConfig.real())
                .providerRoute("CPS_EQB", RouteConfig.mock(UnavailablePolicy.FAST_FAIL))
                .apiRoute("CPS_EQB", "CPS_FLOW_FILES", RouteConfig.real())
                .build();

        RouteDecision decision = resolver.resolve(snapshot, context("TEST", "CPS_EQB", "CPS_FLOW_FILES"));

        assertThat(decision.mode()).isEqualTo(MockMode.REAL);
        assertThat(decision.configVersion()).isEqualTo(7);
    }

    @Test
    void canaryRoutesOnlyDeterministicAllowlistedContextToMock() {
        CanaryRule canary = CanaryRule.builder()
                .app("sample-app")
                .tenant("tenant-a")
                .testAccount("tester-01")
                .build();
        RoutingSnapshot snapshot = snapshot(RouteConfig.canary(canary, UnavailablePolicy.FAST_FAIL));

        RouteDecision matched = resolver.resolve(snapshot, context("TEST", "OA", "CREATE", "tenant-a", "tester-01", false));
        RouteDecision unmatched = resolver.resolve(snapshot, context("TEST", "OA", "CREATE", "tenant-b", "tester-02", false));

        assertThat(matched.mode()).isEqualTo(MockMode.MOCK);
        assertThat(unmatched.mode()).isEqualTo(MockMode.REAL);
    }

    @Test
    void controlledRequestOverrideWorksOnlyWhenSnapshotAllowsIt() {
        CanaryRule canary = CanaryRule.builder().build();
        RoutingSnapshot denied = snapshot(RouteConfig.canary(canary, UnavailablePolicy.FAST_FAIL));
        RoutingSnapshot allowed = RoutingSnapshot.builderFrom(denied).allowRequestOverride(true).build();
        MockContext context = context("TEST", "OA", "CREATE", null, null, true);

        assertThat(resolver.resolve(denied, context).mode()).isEqualTo(MockMode.REAL);
        assertThat(resolver.resolve(allowed, context).mode()).isEqualTo(MockMode.MOCK);
    }

    @Test
    void productionAlwaysRoutesRealEvenWhenConfigurationRequestsMock() {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(3)
                .appCode("sample-app")
                .environment("PROD")
                .runtimeBaseUri(URI.create("http://localhost:9080"))
                .defaultRoute(RouteConfig.mock(UnavailablePolicy.FAST_FAIL))
                .build();

        assertThat(resolver.resolve(snapshot, context("PROD", "OA", "CREATE")).mode()).isEqualTo(MockMode.REAL);
    }

    @Test
    void rejectedNewerConfigurationFailsMockRoutesButPreservesRealRoutes() {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(2)
                .observedConfigVersion(3)
                .mockConfigValid(false)
                .appCode("sample-app")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://localhost:9080"))
                .defaultRoute(RouteConfig.real())
                .providerRoute("OA", RouteConfig.mock(UnavailablePolicy.FAST_FAIL))
                .build();

        assertThat(resolver.resolve(snapshot, context("TEST", "PAY", "CREATE")).mode())
                .isEqualTo(MockMode.REAL);
        assertThatThrownBy(() -> resolver.resolve(snapshot, context("TEST", "OA", "CREATE")))
                .isInstanceOf(MockConfigInvalidException.class)
                .hasMessageContaining("MOCK_CONFIG_INVALID");
    }

    private RoutingSnapshot snapshot(RouteConfig route) {
        return RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("sample-app")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://localhost:9080"))
                .defaultRoute(route)
                .build();
    }

    private MockContext context(String environment, String provider, String api) {
        return context(environment, provider, api, null, null, false);
    }

    private MockContext context(String environment, String provider, String api, String tenant, String account, boolean override) {
        return MockContext.builder()
                .appCode("sample-app")
                .environment(environment)
                .provider(provider)
                .api(api)
                .tenant(tenant)
                .testAccount(account)
                .requestOverrideMock(override)
                .mockRequestId("mr-test")
                .build();
    }
}
