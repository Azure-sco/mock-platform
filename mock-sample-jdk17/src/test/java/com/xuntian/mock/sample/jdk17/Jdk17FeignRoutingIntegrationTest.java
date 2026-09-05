package com.xuntian.mock.sample.jdk17;

import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.config.MockConfigProvider;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.model.UnavailablePolicy;
import com.xuntian.mock.client.core.routing.CanaryRule;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import com.xuntian.mock.fakereal.FakeRealApplication;
import com.xuntian.mock.fakereal.capture.CapturedRealRequest;
import com.xuntian.mock.fakereal.capture.FakeRealRequestCapture;
import com.xuntian.mock.runtime.MockRuntimeApplication;
import com.xuntian.mock.runtime.capture.CapturedRequest;
import com.xuntian.mock.runtime.capture.RuntimeRequestCapture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class Jdk17FeignRoutingIntegrationTest {

    private static ConfigurableApplicationContext fakeReal;
    private static ConfigurableApplicationContext runtime;
    private static ConfigurableApplicationContext sample;
    private static int fakeRealPort;
    private static int runtimePort;
    private static final AtomicLong CONFIG_VERSION = new AtomicLong(1);

    @BeforeAll
    static void startTargets() {
        fakeReal = new SpringApplicationBuilder(FakeRealApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--management.endpoints.enabled-by-default=false",
                        "--xuntian.mock.client.enabled=false",
                        "--spring.autoconfigure.exclude=" + infrastructureAutoConfigurations());
        fakeRealPort = port(fakeReal);

        runtime = new SpringApplicationBuilder(MockRuntimeApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--spring.profiles.active=test",
                        "--spring.flyway.enabled=false",
                        "--management.health.redis.enabled=false",
                        "--xuntian.mock.client.enabled=false",
                        "--xuntian.mock.runtime.environment=TEST",
                        "--xuntian.mock.runtime.local-app-tokens[local-token-17]=sample-jdk17",
                        "--spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration");
        runtimePort = port(runtime);

        sample = new SpringApplicationBuilder(Jdk17SampleApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--cps.base-url=http://127.0.0.1:" + fakeRealPort,
                        "--xuntian.mock.client.app-code=sample-jdk17",
                        "--xuntian.mock.client.environment=TEST",
                        "--xuntian.mock.client.runtime-base-uri=http://127.0.0.1:" + runtimePort,
                        "--xuntian.mock.client.mock-app-token=local-token-17",
                        "--xuntian.mock.client.mode=REAL",
                        "--xuntian.mock.client.tenant=m0-tenant",
                        "--xuntian.mock.client.test-account=m0-account",
                        "--xuntian.mock.client.allowed-business-headers[0]=domain",
                        "--spring.autoconfigure.exclude=" + infrastructureAutoConfigurations());
    }

    @AfterAll
    static void stopTargets() {
        if (sample != null) {
            sample.close();
        }
        if (runtime != null) {
            runtime.close();
        }
        if (fakeReal != null) {
            fakeReal.close();
        }
    }

    @BeforeEach
    void resetRoute() {
        LocalConfigProvider config = localConfig();
        config.update(snapshot(config.current(), RouteConfig.real()));
    }

    @Test
    void dynamicallyRoutesFeignRealMockAndCanaryWithSanitizedCopy() {
        CpsSigningGateway gateway = sample.getBean(CpsSigningGateway.class);
        assertThat(gateway.createAndStart(42L)).contains("FAKE_REAL");

        CapturedRealRequest real = fakeReal.getBean(FakeRealRequestCapture.class).last();
        assertThat(real.authorization()).isEqualTo("Bearer real-cps-secret");
        assertThat(real.cookie()).isEqualTo("cps-session=real-only");
        assertThat(real.signature()).isEqualTo("real-cps-signature");
        int realCallsBeforeMock = fakeReal.getBean(FakeRealRequestCapture.class).count();

        LocalConfigProvider config = localConfig();
        config.update(snapshot(config.current(), RouteConfig.builder(MockMode.MOCK)
                .allowBusinessHeader("domain")
                .build()));
        assertThat(gateway.createAndStart(42L)).contains("M1_FIXTURE");

        CapturedRequest mock = runtime.getBean(RuntimeRequestCapture.class).last();
        assertThat(mock.path()).isEqualTo("/sign/create-and-start");
        assertThat(mock.rawQuery()).isEqualTo("channel=EQB");
        assertThat(mock.provider()).isEqualTo("CPS_EQB");
        assertThat(mock.api()).isEqualTo("CPS_SIGN_CREATE_START");
        assertThat(mock.authorizationScheme()).isEqualTo("MockApp");
        assertThat(mock.headerNames()).anyMatch(name -> name.equalsIgnoreCase("domain"));
        assertThat(mock.headerNames()).noneMatch(name -> isSensitive(name));

        Throwable noMatch = catchThrowable(() -> gateway.createAndStartWithChannel(42L, "UNKNOWN"));
        assertThat(noMatch).isNotNull();
        assertThat(fakeReal.getBean(FakeRealRequestCapture.class).count()).isEqualTo(realCallsBeforeMock);

        CpsFilesRestGateway restGateway = sample.getBean(CpsFilesRestGateway.class);
        assertThat(restGateway.querySignedFiles("REAL-EQB-M0")).contains("M1_FIXTURE");
        CapturedRequest restMock = runtime.getBean(RuntimeRequestCapture.class).last();
        assertThat(restMock.path()).isEqualTo("/flow/get-contract-files");
        assertThat(restMock.api()).isEqualTo("CPS_FLOW_FILES");
        assertThat(restMock.headerNames()).noneMatch(name -> isSensitive(name));

        RouteConfig canary = RouteConfig.builder(MockMode.CANARY)
                .canaryRule(CanaryRule.builder().testAccount("m0-account").build())
                .allowBusinessHeader("domain")
                .build();
        config.update(snapshot(config.current(), canary));
        assertThat(gateway.createAndStart(43L)).contains("M1_FIXTURE");
        assertThat(fakeReal.getBean(FakeRealRequestCapture.class).count()).isEqualTo(realCallsBeforeMock);

        config.update(snapshot(config.current(), RouteConfig.real()));
        assertThat(gateway.createAndStart(44L)).contains("FAKE_REAL");
        assertThat(fakeReal.getBean(FakeRealRequestCapture.class).count()).isEqualTo(realCallsBeforeMock + 1);
    }

    @Test
    void neverFallsBackToRealAfterRuntimeReceivedAndResetConnection() {
        CpsSigningGateway gateway = sample.getBean(CpsSigningGateway.class);
        gateway.createAndStart(1L);
        FakeRealRequestCapture realCapture = fakeReal.getBean(FakeRealRequestCapture.class);
        CapturedRealRequest beforeReset = realCapture.last();
        int realCallsBeforeReset = realCapture.count();

        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_REAL)
                .allowRealHost("127.0.0.1")
                .allowBusinessHeader("domain")
                .build();
        LocalConfigProvider config = localConfig();
        config.update(snapshot(config.current(), route));

        assertThat(gateway.resetProbe("received-before-reset")).isEqualTo("runtime-received");
        assertThat(runtime.getBean(RuntimeRequestCapture.class).last().path()).isEqualTo("/__m0/reset");
        assertThat(realCapture.last()).isSameAs(beforeReset);
        assertThat(realCapture.count()).isEqualTo(realCallsBeforeReset);

        CpsFilesRestGateway restGateway = sample.getBean(CpsFilesRestGateway.class);
        assertThat(restGateway.resetProbe("rest-received-before-reset"))
                .isEqualTo("runtime-received");
        assertThat(runtime.getBean(RuntimeRequestCapture.class).last().path()).isEqualTo("/__m0/reset");
        assertThat(realCapture.last()).isSameAs(beforeReset);
        assertThat(realCapture.count()).isEqualTo(realCallsBeforeReset);

        assertThat(gateway.createAndStart(2L)).contains("M1_FIXTURE");
        assertThat(realCapture.count()).isEqualTo(realCallsBeforeReset);
    }

    private LocalConfigProvider localConfig() {
        return (LocalConfigProvider) sample.getBean(MockConfigProvider.class);
    }

    private RoutingSnapshot snapshot(RoutingSnapshot current, RouteConfig route) {
        return RoutingSnapshot.builderFrom(current)
                .configVersion(CONFIG_VERSION.incrementAndGet())
                .runtimeBaseUri(URI.create("http://127.0.0.1:" + runtimePort))
                .defaultRoute(route)
                .build();
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static String infrastructureAutoConfigurations() {
        return "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration";
    }

    private boolean isSensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("cookie")
                || normalized.equals("x-signature")
                || normalized.equals("x-app-secret");
    }
}
