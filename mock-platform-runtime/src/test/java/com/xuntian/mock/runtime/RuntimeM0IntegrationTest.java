package com.xuntian.mock.runtime;

import com.xuntian.mock.runtime.capture.CapturedRequest;
import com.xuntian.mock.runtime.capture.RuntimeRequestCapture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(
        classes = MockRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "xuntian.mock.runtime.environment=TEST",
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
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class RuntimeM0IntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RuntimeRequestCapture capture;

    @LocalServerPort
    private int port;

    @Test
    void authenticatesAppAndReturnsFixedCpsResponseWithRequestId() {
        webTestClient.post()
                .uri("/sign/create-and-start?channel=EQB")
                .header("Authorization", "MockApp local-token")
                .header("X-Mock-App", "forged-app")
                .header("X-Mock-Provider", "CPS_EQB")
                .header("X-Mock-Api", "CPS_SIGN_CREATE_START")
                .header("X-Mock-Request-Id", "mr-e2e-1")
                .header("domain", "cps-test")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"settleId\":42}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Mock-Request-Id", "mr-e2e-1")
                .expectBody()
                .jsonPath("$.source").isEqualTo("M0_FIXED")
                .jsonPath("$.data.flowId").isEqualTo("MOCK-EQB-mr-e2e-1");

        CapturedRequest seen = capture.last();
        assertThat(seen.appCode()).isEqualTo("sample-jdk17");
        assertThat(seen.environment()).isEqualTo("TEST");
        assertThat(seen.authorizationScheme()).isEqualTo("MockApp");
        assertThat(seen.headerNames()).contains("domain");
        assertThat(seen.headerNames()).doesNotContain("Cookie", "X-Signature", "X-App-Secret");
    }

    @Test
    void rejectsUnknownMockAppTokenWithoutTrustingSelfReportedApp() {
        webTestClient.get()
                .uri("/oa/number")
                .header("Authorization", "MockApp wrong-token")
                .header("X-Mock-App", "sample-jdk17")
                .header("X-Mock-Provider", "OA")
                .header("X-Mock-Api", "OA_NUMBER_QUERY")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("MOCK_APP_UNAUTHORIZED");
    }

    @Test
    void rejectsBodyLargerThanOneMegabyte() {
        webTestClient.post()
                .uri("/sign/create-and-start")
                .header("Authorization", "MockApp local-token")
                .header("X-Mock-Provider", "CPS_EQB")
                .header("X-Mock-Api", "CPS_SIGN_CREATE_START")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(new byte[1024 * 1024 + 1])
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void resetPocClosesConnectionAfterRuntimeReceivesRequest() {
        Throwable failure = catchThrowable(() -> HttpClient.create()
                .baseUrl("http://127.0.0.1:" + port)
                .headers(headers -> {
                    headers.set("Authorization", "MockApp local-token");
                    headers.set("X-Mock-Provider", "CPS_EQB");
                    headers.set("X-Mock-Api", "CPS_SIGN_CREATE_START");
                    headers.set("X-Mock-Request-Id", "mr-reset-1");
                })
                .post()
                .uri("/__m0/reset")
                .send(ByteBufFlux.fromString(Mono.just("request-received")))
                .responseContent()
                .aggregate()
                .asString()
                .block(Duration.ofSeconds(5)));

        assertThat(failure).isNotNull();
        assertThat(capture.last().path()).isEqualTo("/__m0/reset");
        assertThat(capture.last().requestId()).isEqualTo("mr-reset-1");

        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }
}
