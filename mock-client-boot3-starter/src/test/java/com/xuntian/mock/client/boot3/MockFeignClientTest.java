package com.xuntian.mock.client.boot3;

import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.context.MockContextHolder;
import com.xuntian.mock.client.core.failure.MockRuntimeUnavailableException;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.model.UnavailablePolicy;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.RequestLine;
import feign.Response;
import feign.Retryer;
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
    void routesSanitizedImmutableRequestCopyOnJdk17() throws Exception {
        RecordingClient delegate = new RecordingClient();
        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .allowBusinessHeader("domain")
                .build();
        Request original = request();

        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-j17-1"))) {
            client(delegate, route).execute(original, new Request.Options());
        }

        Request sent = delegate.requests.get(0);
        assertThat(sent.url()).isEqualTo("http://runtime:19091/sign/create?channel=EQB");
        assertThat(sent.body()).containsExactly(original.body());
        assertThat(sent.headers().get("Authorization")).containsExactly("MockApp token-17");
        assertThat(sent.headers().get("domain")).containsExactly("cps-test");
        assertThat(sent.headers().get("X-Mock-Request-Id")).containsExactly("mr-j17-1");
        assertThat(original.headers().get("Authorization")).containsExactly("Bearer real-secret");
    }

    @Test
    void permitsOnlyApprovedConnectionFailureToReachRealHost() throws Exception {
        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_REAL)
                .allowRealHost("real.example.com")
                .build();
        RecordingClient connectionFailure = new RecordingClient();
        connectionFailure.mockFailure = new ConnectException("refused");
        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-j17-2"))) {
            client(connectionFailure, route).execute(request(), new Request.Options());
        }
        assertThat(connectionFailure.requests).extracting(Request::url).containsExactly(
                "http://runtime:19091/sign/create?channel=EQB",
                "https://real.example.com/sign/create?channel=EQB");

        RecordingClient readTimeout = new RecordingClient();
        readTimeout.mockFailure = new SocketTimeoutException("read timed out");
        assertThatThrownBy(() -> {
            try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-j17-3"))) {
                client(readTimeout, route).execute(request(), new Request.Options());
            }
        }).isInstanceOf(MockRuntimeUnavailableException.class);
        assertThat(readTimeout.requests).hasSize(1);
    }

    @Test
    void reusesMockRequestIdAcrossBottomFeignRetry() {
        RetryingClient delegate = new RetryingClient();
        MockFeignClient mockClient = client(delegate, RouteConfig.mock(UnavailablePolicy.FAST_FAIL));
        RetryApi api = Feign.builder()
                .client(mockClient)
                .retryer(new Retryer.Default(1, 1, 2))
                .target(RetryApi.class, "https://real.example.com");

        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-j17-retry"))) {
            assertThat(api.call()).isEqualTo("ok");
        }

        assertThat(delegate.requests).hasSize(2);
        assertThat(delegate.requests)
                .extracting(request -> request.headers().get("X-Mock-Request-Id"))
                .allSatisfy(values -> assertThat(values).containsExactly("mr-j17-retry"));
    }

    private MockFeignClient client(Client delegate, RouteConfig route) {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("sample-jdk17")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://runtime:19091"))
                .defaultRoute(route)
                .build();
        return new MockFeignClient(delegate, new LocalConfigProvider(snapshot), "token-17");
    }

    private Request request() {
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", Collections.singletonList("application/json"));
        headers.put("domain", Collections.singletonList("cps-test"));
        headers.put("Authorization", Collections.singletonList("Bearer real-secret"));
        return Request.create(
                Request.HttpMethod.POST,
                "https://real.example.com/sign/create?channel=EQB",
                headers,
                "{\"settleId\":42}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private MockContext context(String requestId) {
        return MockContext.builder()
                .appCode("sample-jdk17")
                .environment("TEST")
                .provider("CPS_EQB")
                .api("CPS_SIGN_CREATE_START")
                .mockRequestId(requestId)
                .build();
    }

    private static final class RecordingClient implements Client {
        private final List<Request> requests = new ArrayList<>();
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

    private interface RetryApi {
        @RequestLine("GET /retry")
        String call();
    }

    private static final class RetryingClient implements Client {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            requests.add(request);
            if (requests.size() == 1) {
                throw new IOException("runtime reset after receive");
            }
            return Response.builder()
                    .status(200)
                    .reason("OK")
                    .request(request)
                    .body("ok", StandardCharsets.UTF_8)
                    .build();
        }
    }
}
