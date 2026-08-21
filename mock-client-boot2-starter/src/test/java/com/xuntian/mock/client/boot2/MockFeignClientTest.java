package com.xuntian.mock.client.boot2;

import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.context.MockContextHolder;
import com.xuntian.mock.client.core.failure.MockRuntimeUnavailableException;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.model.UnavailablePolicy;
import com.xuntian.mock.client.core.routing.FallbackResponse;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import feign.Client;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockFeignClientTest {

    @Test
    void sendsSanitizedCopyToRuntimeAndLeavesOriginalUntouched() throws Exception {
        RecordingClient delegate = new RecordingClient();
        MockFeignClient client = client(delegate, route(UnavailablePolicy.FAST_FAIL));
        Request original = request();

        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-1"))) {
            client.execute(original, new Request.Options());
        }

        Request sent = delegate.requests.get(0);
        assertThat(sent.url()).isEqualTo("http://runtime:19091/sign/create?channel=EQB&name=a%20b");
        assertThat(sent.httpMethod()).isEqualTo(Request.HttpMethod.POST);
        assertThat(sent.body()).containsExactly(original.body());
        assertThat(sent.headers()).containsEntry("domain", Collections.<String>singletonList("cps-test"));
        assertThat(sent.headers()).containsEntry("Authorization", Collections.<String>singletonList("MockApp token-1"));
        assertThat(sent.headers()).containsEntry("X-Mock-Provider", Collections.<String>singletonList("CPS_EQB"));
        assertThat(sent.headers()).containsEntry("X-Mock-Request-Id", Collections.<String>singletonList("mr-1"));
        assertThat(original.url()).isEqualTo("https://real.example.com/sign/create?channel=EQB&name=a%20b");
        assertThat(original.headers()).containsEntry("Authorization", Collections.<String>singletonList("Bearer real-secret"));
    }

    @Test
    void fallsBackToOriginalOnlyAfterApprovedConnectionFailure() throws Exception {
        RecordingClient delegate = new RecordingClient();
        delegate.mockFailure = new ConnectException("refused");
        MockFeignClient client = client(delegate, RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_REAL)
                .allowRealHost("real.example.com")
                .allowBusinessHeader("domain")
                .build());

        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-2"))) {
            client.execute(request(), new Request.Options());
        }

        assertThat(delegate.requests).extracting(Request::url).containsExactly(
                "http://runtime:19091/sign/create?channel=EQB&name=a%20b",
                "https://real.example.com/sign/create?channel=EQB&name=a%20b");
        assertThat(delegate.requests.get(1).headers())
                .containsEntry("Authorization", Collections.<String>singletonList("Bearer real-secret"));
    }

    @Test
    void neverFallsBackToRealAfterReadTimeout() {
        RecordingClient delegate = new RecordingClient();
        delegate.mockFailure = new SocketTimeoutException("read timed out");
        MockFeignClient client = client(delegate, RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_REAL)
                .allowRealHost("real.example.com")
                .build());

        assertThatThrownBy(() -> {
            try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-3"))) {
                client.execute(request(), new Request.Options());
            }
        }).isInstanceOf(MockRuntimeUnavailableException.class)
                .hasMessageContaining("mr-3");
        assertThat(delegate.requests).hasSize(1);
    }

    @Test
    void returnsHttpFallbackResponseThroughFeignChain() throws Exception {
        RecordingClient delegate = new RecordingClient();
        delegate.mockFailure = new ConnectException("refused");
        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_RESPONSE)
                .fallbackResponse(new FallbackResponse(503, "application/json", "{\"code\":\"MOCK_DOWN\"}"))
                .build();

        Response response;
        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-4"))) {
            response = client(delegate, route).execute(request(), new Request.Options());
        }

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.headers().get("Content-Type")).containsExactly("application/json");
        assertThat(response.body().asInputStream()).hasContent("{\"code\":\"MOCK_DOWN\"}");
        assertThat(delegate.requests).hasSize(1);
    }

    private MockFeignClient client(RecordingClient delegate, RouteConfig route) {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("pomp-power")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://runtime:19091"))
                .defaultRoute(route)
                .build();
        return new MockFeignClient(delegate, new LocalConfigProvider(snapshot), "token-1");
    }

    private RouteConfig route(UnavailablePolicy policy) {
        return RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(policy)
                .allowBusinessHeader("domain")
                .build();
    }

    private Request request() {
        Map<String, Collection<String>> headers = new LinkedHashMap<String, Collection<String>>();
        headers.put("Content-Type", Collections.singletonList("application/json"));
        headers.put("domain", Collections.singletonList("cps-test"));
        headers.put("Authorization", Collections.singletonList("Bearer real-secret"));
        return Request.create(
                Request.HttpMethod.POST,
                "https://real.example.com/sign/create?channel=EQB&name=a%20b",
                headers,
                "{\"settleId\":42}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private MockContext context(String requestId) {
        return MockContext.builder()
                .appCode("pomp-power")
                .environment("TEST")
                .provider("CPS_EQB")
                .api("CPS_SIGN_CREATE_START")
                .mockRequestId(requestId)
                .build();
    }

    private static final class RecordingClient implements Client {
        private final List<Request> requests = new ArrayList<Request>();
        private IOException mockFailure;

        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            requests.add(request);
            if (request.url().startsWith("http://runtime") && mockFailure != null) {
                throw mockFailure;
            }
            return Response.builder().status(200).reason("OK").request(request).build();
        }
    }
}
