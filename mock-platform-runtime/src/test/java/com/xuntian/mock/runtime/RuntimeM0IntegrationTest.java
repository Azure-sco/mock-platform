package com.xuntian.mock.runtime;

import com.xuntian.mock.runtime.capture.CapturedRequest;
import com.xuntian.mock.runtime.capture.RuntimeRequestCapture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = MockRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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

    @Test
    void authenticatesAppAndReturnsCompiledFixtureScenarioWithRuntimeHeaders() {
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
                .expectHeader().valueEquals("X-Mock-Scenario-Id", "sc-cps-sign-success")
                .expectHeader().valueEquals("X-Mock-Release-Id", "rel-local-fixture-v1")
                .expectHeader().valueEquals("X-Mock-Activation-Version", "0")
                .expectBody()
                .jsonPath("$.source").isEqualTo("M1_FIXTURE")
                .jsonPath("$.data.flowId").isEqualTo("MOCK-EQB-mr-e2e-1")
                .jsonPath("$.data.settleId").isEqualTo(42);

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
                .jsonPath("$.code").isEqualTo("MOCK_APP_UNAUTHORIZED")
                .jsonPath("$.mockRequestId").isNotEmpty()
                .jsonPath("$.traceId").isNotEmpty();
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
                .jsonPath("$.code").isEqualTo("MOCK_REQUEST_TOO_LARGE")
                .jsonPath("$.mockRequestId").isNotEmpty()
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void returnsNoMatchInsteadOfFallingBackToM0Dispatcher() {
        webTestClient.post()
                .uri("/sign/create-and-start?channel=OTHER")
                .header("Authorization", "MockApp local-token")
                .header("X-Mock-Provider", "CPS_EQB")
                .header("X-Mock-Api", "CPS_SIGN_CREATE_START")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"settleId\":42}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("MOCK_NO_MATCH");
    }

    @Test
    void selectsTenantScopedScenarioWithoutLeakingItToAnotherTenant() {
        webTestClient.post()
                .uri("/sign/create-and-start?channel=EQB")
                .header("Authorization", "MockApp local-token")
                .header("X-Mock-Tenant", "tenant-a")
                .header("X-Mock-Test-Account", "tester-01")
                .header("X-Mock-Provider", "CPS_EQB")
                .header("X-Mock-Api", "CPS_SIGN_CREATE_START")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"settleId\":42}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Mock-Scenario-Id", "sc-cps-sign-tenant-a")
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("TENANT_SIGNING");

        webTestClient.post()
                .uri("/sign/create-and-start?channel=EQB")
                .header("Authorization", "MockApp local-token")
                .header("X-Mock-Tenant", "tenant-b")
                .header("X-Mock-Test-Account", "tester-01")
                .header("X-Mock-Provider", "CPS_EQB")
                .header("X-Mock-Api", "CPS_SIGN_CREATE_START")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"settleId\":42}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Mock-Scenario-Id", "sc-cps-sign-success");
    }

    @Test
    void rejectsRequestThatDoesNotMatchPublishedPath() {
        webTestClient.post()
                .uri("/wrong-path?channel=EQB")
                .header("Authorization", "MockApp local-token")
                .header("X-Mock-Provider", "CPS_EQB")
                .header("X-Mock-Api", "CPS_SIGN_CREATE_START")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"settleId\":42}")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("MOCK_CONTRACT_MISMATCH");
    }

    @Test
    void rejectsOversizedRequestIdentifiersWithoutEchoingThem() {
        String invalid = "x".repeat(65);
        webTestClient.post()
                .uri("/sign/create-and-start?channel=EQB")
                .header("Authorization", "MockApp local-token")
                .header("X-Mock-Request-Id", invalid)
                .header("X-Mock-Provider", "CPS_EQB")
                .header("X-Mock-Api", "CPS_SIGN_CREATE_START")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"settleId\":42}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().value("X-Mock-Request-Id", value -> assertThat(value).isNotEqualTo(invalid))
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    void resetPocCapturesRequestInTestProfile() {
        webTestClient
                .post()
                .uri("/__m0/reset")
                .header("Authorization", "MockApp local-token")
                .header("X-Mock-Provider", "CPS_EQB")
                .header("X-Mock-Api", "CPS_SIGN_CREATE_START")
                .header("X-Mock-Request-Id", "mr-reset-1")
                .bodyValue("request-received")
                .exchange();

        assertThat(capture.last().path()).isEqualTo("/__m0/reset");
        assertThat(capture.last().requestId()).isEqualTo("mr-reset-1");

        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }
}
