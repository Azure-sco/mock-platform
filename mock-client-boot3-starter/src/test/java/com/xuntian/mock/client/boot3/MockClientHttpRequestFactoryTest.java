package com.xuntian.mock.client.boot3;

import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.context.MockContextHolder;
import com.xuntian.mock.client.core.failure.MockRuntimeUnavailableException;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.model.UnavailablePolicy;
import com.xuntian.mock.client.core.routing.FallbackResponse;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockClientHttpRequestFactoryTest {

    @Test
    void preservesBodyAndReturnsConfiguredHttpFallback() throws Exception {
        RecordingFactory delegate = new RecordingFactory();
        delegate.mockFailure = new ConnectException("refused");
        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_RESPONSE)
                .fallbackResponse(new FallbackResponse(503, "application/json", "{\"code\":\"DOWN\"}"))
                .allowBusinessHeader("domain")
                .build();

        ClientHttpResponse response;
        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-rest-17-1"))) {
            ClientHttpRequest request = factory(delegate, route)
                    .createRequest(URI.create("https://real.example.com/sign/create?q=a%20b"), HttpMethod.POST);
            request.getHeaders().add("domain", "cps-test");
            request.getHeaders().add("Authorization", "Bearer real-secret");
            request.getBody().write("payload".getBytes(StandardCharsets.UTF_8));
            response = request.execute();
            assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer real-secret");
        }

        RecordedRequest sent = delegate.requests.get(0);
        assertThat(sent.uri.toString()).isEqualTo("http://runtime:19091/sign/create?q=a%20b");
        assertThat(sent.body.toByteArray()).asString(StandardCharsets.UTF_8).isEqualTo("payload");
        assertThat(sent.getHeaders().getFirst("Authorization")).isEqualTo("MockApp token-17");
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).hasContent("{\"code\":\"DOWN\"}");
    }

    @Test
    void neverRetriesRealAfterReadTimeout() {
        RecordingFactory delegate = new RecordingFactory();
        delegate.mockFailure = new SocketTimeoutException("read timed out");
        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_REAL)
                .allowRealHost("real.example.com")
                .build();

        assertThatThrownBy(() -> {
            try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-rest-17-2"))) {
                factory(delegate, route)
                        .createRequest(URI.create("https://real.example.com/query"), HttpMethod.GET)
                        .execute();
            }
        }).isInstanceOf(MockRuntimeUnavailableException.class);
        assertThat(delegate.requests).hasSize(1);
    }

    private MockClientHttpRequestFactory factory(RecordingFactory delegate, RouteConfig route) {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("sample-jdk17")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://runtime:19091"))
                .defaultRoute(route)
                .build();
        return new MockClientHttpRequestFactory(delegate, new LocalConfigProvider(snapshot), "token-17");
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

    private static final class RecordingFactory implements ClientHttpRequestFactory {
        private final List<RecordedRequest> requests = new ArrayList<>();
        private IOException mockFailure;

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            RecordedRequest request = new RecordedRequest(uri, httpMethod, this);
            requests.add(request);
            return request;
        }
    }

    private static final class RecordedRequest extends AbstractClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final RecordingFactory owner;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        private RecordedRequest(URI uri, HttpMethod method, RecordingFactory owner) {
            this.uri = uri;
            this.method = method;
            this.owner = owner;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) {
            return body;
        }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
            if (uri.toString().startsWith("http://runtime") && owner.mockFailure != null) {
                throw owner.mockFailure;
            }
            return new TestResponse(200, "{}");
        }
    }

    private static final class TestResponse implements ClientHttpResponse {
        private final int status;
        private final byte[] body;
        private final HttpHeaders headers = new HttpHeaders();

        private TestResponse(int status, String body) {
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
            headers.set("Content-Type", "application/json");
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatusCode.valueOf(status);
        }

        @Override
        public int getRawStatusCode() {
            return status;
        }

        @Override
        public String getStatusText() {
            return "OK";
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
        }
    }
}
