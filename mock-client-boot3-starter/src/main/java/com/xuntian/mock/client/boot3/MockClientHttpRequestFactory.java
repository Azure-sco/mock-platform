package com.xuntian.mock.client.boot3;

import com.xuntian.mock.client.config.MockConfigProvider;
import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.context.MockContextHolder;
import com.xuntian.mock.client.core.failure.FailureAction;
import com.xuntian.mock.client.core.failure.MockFailurePolicy;
import com.xuntian.mock.client.core.failure.MockRuntimeUnavailableException;
import com.xuntian.mock.client.core.http.UriRewriter;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.routing.FallbackResponse;
import com.xuntian.mock.client.core.routing.RouteDecision;
import com.xuntian.mock.client.core.routing.RouteResolver;
import com.xuntian.mock.client.core.security.MockHeaders;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MockClientHttpRequestFactory implements ClientHttpRequestFactory {

    private final ClientHttpRequestFactory delegate;
    private final MockConfigProvider configProvider;
    private final String mockAppToken;
    private final RouteResolver routeResolver = new RouteResolver();

    public MockClientHttpRequestFactory(
            ClientHttpRequestFactory delegate,
            MockConfigProvider configProvider,
            String mockAppToken) {
        this.delegate = delegate;
        this.configProvider = configProvider;
        this.mockAppToken = mockAppToken;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        Optional<MockContext> current = MockContextHolder.current();
        if (current.isEmpty()) {
            return delegate.createRequest(uri, httpMethod);
        }
        MockContext context = current.get();
        RouteDecision decision = routeResolver.resolve(configProvider.current(), context);
        if (decision.mode() == MockMode.REAL) {
            return delegate.createRequest(uri, httpMethod);
        }
        return new RoutingRequest(uri, httpMethod, context, decision);
    }

    private final class RoutingRequest extends AbstractClientHttpRequest {
        private final URI originalUri;
        private final HttpMethod method;
        private final MockContext context;
        private final RouteDecision decision;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        private RoutingRequest(URI originalUri, HttpMethod method, MockContext context, RouteDecision decision) {
            this.originalUri = originalUri;
            this.method = method;
            this.context = context;
            this.decision = decision;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return originalUri;
        }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) {
            return body;
        }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders originalHeaders) throws IOException {
            URI mockUri = UriRewriter.rewrite(originalUri, decision.runtimeBaseUri());
            try {
                return executeCopy(mockUri, sanitize(originalHeaders));
            } catch (IOException failure) {
                FailureAction action = MockFailurePolicy.decide(context, decision, originalUri, true, failure);
                if (action == FailureAction.FALLBACK_REAL) {
                    return executeCopy(originalUri, copy(originalHeaders));
                }
                if (action == FailureAction.FALLBACK_RESPONSE) {
                    return new StaticResponse(decision.routeConfig().fallbackResponse());
                }
                throw new MockRuntimeUnavailableException(context.mockRequestId(), failure);
            }
        }

        private ClientHttpResponse executeCopy(URI uri, HttpHeaders headers) throws IOException {
            ClientHttpRequest request = delegate.createRequest(uri, method);
            request.getHeaders().putAll(headers);
            request.getBody().write(body.toByteArray());
            return request.execute();
        }

        private HttpHeaders sanitize(HttpHeaders originalHeaders) {
            Map<String, List<String>> values = MockHeaders.build(
                    originalHeaders, context, decision.routeConfig(), mockAppToken);
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(values);
            return headers;
        }

        private HttpHeaders copy(HttpHeaders source) {
            HttpHeaders copy = new HttpHeaders();
            source.forEach((name, values) -> copy.put(name, new ArrayList<>(values)));
            return copy;
        }
    }

    private static final class StaticResponse implements ClientHttpResponse {
        private final FallbackResponse fallback;
        private final HttpHeaders headers = new HttpHeaders();
        private final byte[] body;

        private StaticResponse(FallbackResponse fallback) {
            this.fallback = fallback;
            headers.set("Content-Type", fallback.contentType());
            body = fallback.body().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatusCode.valueOf(fallback.status());
        }

        @Override
        public int getRawStatusCode() {
            return fallback.status();
        }

        @Override
        public String getStatusText() {
            return "Mock Runtime unavailable";
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
