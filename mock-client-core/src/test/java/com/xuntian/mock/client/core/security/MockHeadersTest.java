package com.xuntian.mock.client.core.security;

import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.routing.RouteConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockHeadersTest {

    @Test
    void buildsSanitizedHeadersAndOverwritesUntrustedMockHeaders() {
        Map<String, List<String>> original = new LinkedHashMap<String, List<String>>();
        original.put("Content-Type", Collections.singletonList("application/json"));
        original.put("domain", Collections.singletonList("cps-test"));
        original.put("Authorization", Collections.singletonList("Bearer real-secret"));
        original.put("X-Mock-Provider", Collections.singletonList("forged"));

        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .allowBusinessHeader("domain")
                .allowBusinessHeader("X-Mock-Provider")
                .build();
        MockContext context = MockContext.builder()
                .appCode("pomp-power")
                .environment("TEST")
                .provider("CPS_EQB")
                .api("CPS_SIGN_CREATE_START")
                .tenant("tenant-a")
                .testAccount("tester-01")
                .traceId("trace-1")
                .businessNo("SETTLE-42")
                .mockRequestId("mr-1")
                .build();

        Map<String, List<String>> mockHeaders = MockHeaders.build(original, context, route, "local-token");

        assertThat(mockHeaders)
                .containsEntry("Content-Type", Collections.singletonList("application/json"))
                .containsEntry("domain", Collections.singletonList("cps-test"))
                .containsEntry("Authorization", Collections.singletonList("MockApp local-token"))
                .containsEntry("X-Mock-Provider", Collections.singletonList("CPS_EQB"))
                .containsEntry("X-Mock-Api", Collections.singletonList("CPS_SIGN_CREATE_START"))
                .containsEntry("X-Mock-Tenant", Collections.singletonList("tenant-a"))
                .containsEntry("X-Mock-Test-Account", Collections.singletonList("tester-01"))
                .containsEntry("X-Mock-Trace-Id", Collections.singletonList("trace-1"))
                .containsEntry("X-Mock-Business-No", Collections.singletonList("SETTLE-42"))
                .containsEntry("X-Mock-Request-Id", Collections.singletonList("mr-1"));
        assertThat(original.get("Authorization")).containsExactly("Bearer real-secret");
        assertThat(original.get("X-Mock-Provider")).containsExactly("forged");
    }

    @Test
    void omitsOptionalHeadersAndAuthorizationWhenValuesAreBlank() {
        MockContext context = MockContext.builder()
                .appCode("app")
                .environment("TEST")
                .provider("OA")
                .api("OA_CREATE")
                .mockRequestId("mr-2")
                .build();

        Map<String, List<String>> headers = MockHeaders.build(
                Collections.<String, List<String>>emptyMap(), context, RouteConfig.real(), "");

        assertThat(headers.keySet()).containsExactlyInAnyOrder(
                "X-Mock-Provider", "X-Mock-Api", "X-Mock-Request-Id");
        assertThat(headers).doesNotContainKeys(
                "Authorization", "X-Mock-Tenant", "X-Mock-Test-Account",
                "X-Mock-Trace-Id", "X-Mock-Business-No");
    }
}
