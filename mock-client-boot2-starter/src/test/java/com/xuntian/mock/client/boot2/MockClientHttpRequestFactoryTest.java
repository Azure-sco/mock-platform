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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
    void sendsSanitizedBufferedCopyAndPreservesOriginalHeaders() throws Exception {
        RecordingFactory delegate = new RecordingFactory();
        ClientHttpRequest request;
        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-rest-1"))) {
            request = factory(delegate, baseRoute(UnavailablePolicy.FAST_FAIL))
                    .createRequest(URI.create("https://real.example.com/oa/create?a=1%202"), HttpMethod.POST);
            request.getHeaders().setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
            request.getHeaders().add("Authorization", "Bearer real-secret");
            request.getHeaders().add("X-OA-Business", "settle");
            request.getBody().write("multipart-body".getBytes(StandardCharsets.UTF_8));
            request.execute().close();

            assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer real-secret");
        }

        RecordedRequest sent = delegate.requests.get(0);
        assertThat(sent.uri.toString()).isEqualTo("http://runtime:19091/oa/create?a=1%202");
        assertThat(sent.body.toByteArray()).asString(StandardCharsets.UTF_8).isEqualTo("multipart-body");
        assertThat(sent.getHeaders().getFirst("Authorization")).isEqualTo("MockApp token-1");
        assertThat(sent.getHeaders().getFirst("X-OA-Business")).isEqualTo("settle");
        assertThat(sent.getHeaders().getFirst("X-Mock-Request-Id")).isEqualTo("mr-rest-1");
    }

    @Test
    void fallsBackToOriginalOnlyForConnectionFailure() throws Exception {
        RecordingFactory delegate = new RecordingFactory();
        delegate.mockFailure = new ConnectException("refused");
        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_REAL)
                .allowRealHost("real.example.com")
                .build();

        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-rest-2"))) {
            ClientHttpRequest request = factory(delegate, route)
                    .createRequest(URI.create("https://real.example.com/oa/query"), HttpMethod.GET);
            request.getHeaders().add("Authorization", "Bearer real-secret");
            request.execute().close();
        }

        assertThat(delegate.requests).extracting(recorded -> recorded.uri.toString()).containsExactly(
                "http://runtime:19091/oa/query", "https://real.example.com/oa/query");
        assertThat(delegate.requests.get(1).getHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer real-secret");
    }

    @Test
    void doesNotFallBackAfterReadTimeout() {
        RecordingFactory delegate = new RecordingFactory();
        delegate.mockFailure = new SocketTimeoutException("read timed out");
        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_REAL)
                .allowRealHost("real.example.com")
                .build();

        assertThatThrownBy(() -> {
            try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-rest-3"))) {
                factory(delegate, route)
                        .createRequest(URI.create("https://real.example.com/oa/query"), HttpMethod.GET)
                        .execute();
            }
        }).isInstanceOf(MockRuntimeUnavailableException.class);
        assertThat(delegate.requests).hasSize(1);
    }

    @Test
    void synthesizesConfiguredRestTemplateResponse() throws Exception {
        RecordingFactory delegate = new RecordingFactory();
        delegate.mockFailure = new ConnectException("refused");
        RouteConfig route = RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(UnavailablePolicy.FALLBACK_RESPONSE)
                .fallbackResponse(new FallbackResponse(502, "application/json", "{\"code\":\"DOWN\"}"))
                .build();

        ClientHttpResponse response;
        try (MockContextHolder.Scope ignored = MockContextHolder.push(context("mr-rest-4"))) {
            response = factory(delegate, route)
                    .createRequest(URI.create("https://real.example.com/oa/query"), HttpMethod.GET)
                    .execute();
        }

        assertThat(response.getRawStatusCode()).isEqualTo(502);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
        assertThat(response.getBody()).hasContent("{\"code\":\"DOWN\"}");
    }

    private MockClientHttpRequestFactory factory(RecordingFactory delegate, RouteConfig route) {
        RoutingSnapshot snapshot = RoutingSnapshot.builder()
                .configVersion(1)
                .appCode("pomp-power")
                .environment("TEST")
                .runtimeBaseUri(URI.create("http://runtime:19091"))
                .defaultRoute(route)
                .build();
        return new MockClientHttpRequestFactory(delegate, new LocalConfigProvider(snapshot), "token-1");
    }

    private RouteConfig baseRoute(UnavailablePolicy policy) {
        return RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(policy)
                .allowBusinessHeader("X-OA-Business")
                .build();
    }

    private MockContext context(String requestId) {
        return MockContext.builder()
                .appCode("pomp-power")
                .environment("TEST")
                .provider("OA")
                .api("OA_SETTLE_CREATE")
                .mockRequestId(requestId)
                .build();
    }

    private static final class RecordingFactory implements ClientHttpRequestFactory {
        private final List<RecordedRequest> requests = new ArrayList<RecordedRequest>();
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
        public String getMethodValue() {
            return method.name();
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
            return new TestResponse(200, "application/json", "{}");
        }
    }

    private static final class TestResponse implements ClientHttpResponse {
        private final int status;
        private final HttpHeaders headers = new HttpHeaders();
        private final byte[] body;

        private TestResponse(int status, String contentType, String body) {
            this.status = status;
            this.headers.set("Content-Type", contentType);
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public HttpStatus getStatusCode() {
            return HttpStatus.valueOf(status);
        }

        @Override
        public int getRawStatusCode() {
            return status;
        }

        @Override
        public String getStatusText() {
            return getStatusCode().getReasonPhrase();
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
