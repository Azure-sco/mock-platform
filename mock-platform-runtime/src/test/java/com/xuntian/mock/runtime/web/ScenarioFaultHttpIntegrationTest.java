package com.xuntian.mock.runtime.web;

import com.xuntian.mock.runtime.MockRuntimeApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = MockRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "xuntian.mock.runtime.environment=TEST",
                "xuntian.mock.runtime.fixture-location=classpath:mvp-demo-fixture.json",
                "xuntian.mock.runtime.local-app-tokens[local-token]=sample-jdk17",
                "spring.flyway.enabled=false",
                "management.health.redis.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
        })
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "mock.integration.netty-fault", matches = "true")
class ScenarioFaultHttpIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void readTimeoutClosesActualNettyConnectionWithoutSendingAnHttp500() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/b2b/org-auth-check"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "MockApp local-token")
                .header("X-Mock-Provider", "CPS_EQB")
                .header("X-Mock-Api", "CPS_ORG_AUTH_CHECK")
                .header("X-Mock-Request-Id", "mr-fault-http-1")
                .header("X-Mock-Explicit-Scenario", "eqb-auth-timeout")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofByteArray()))
                .isInstanceOf(IOException.class);
    }
}
