package com.xuntian.mock.client.boot2;

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
import feign.Client;
import feign.Request;
import feign.Response;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MockFeignClient implements Client {

    private final Client delegate;
    private final MockConfigProvider configProvider;
    private final String mockAppToken;
    private final RouteResolver routeResolver = new RouteResolver();

    public MockFeignClient(Client delegate, MockConfigProvider configProvider, String mockAppToken) {
        this.delegate = delegate;
        this.configProvider = configProvider;
        this.mockAppToken = mockAppToken;
    }

    @Override
    public Response execute(Request original, Request.Options options) throws IOException {
        Optional<MockContext> current = MockContextHolder.current();
        if (!current.isPresent()) {
            return delegate.execute(original, options);
        }
        MockContext context = current.get();
        RouteDecision decision = routeResolver.resolve(configProvider.current(), context);
        if (decision.mode() == MockMode.REAL) {
            return delegate.execute(original, options);
        }

        URI originalUri = URI.create(original.url());
        Request mockRequest = copyForMock(original, context, decision);
        try {
            return delegate.execute(mockRequest, options);
        } catch (IOException failure) {
            FailureAction action = MockFailurePolicy.decide(context, decision, originalUri, true, failure);
            if (action == FailureAction.FALLBACK_REAL) {
                return delegate.execute(original, options);
            }
            if (action == FailureAction.FALLBACK_RESPONSE) {
                return fallbackResponse(original, decision.routeConfig().fallbackResponse());
            }
            throw new MockRuntimeUnavailableException(context.mockRequestId(), failure);
        }
    }

    private Request copyForMock(Request original, MockContext context, RouteDecision decision) {
        Map<String, List<String>> sanitized = MockHeaders.build(
                original.headers(), context, decision.routeConfig(), mockAppToken);
        Map<String, Collection<String>> headers = new LinkedHashMap<String, Collection<String>>();
        for (Map.Entry<String, List<String>> entry : sanitized.entrySet()) {
            headers.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
        byte[] body = original.body() == null ? null : original.body().clone();
        return Request.create(
                original.httpMethod(),
                UriRewriter.rewrite(URI.create(original.url()), decision.runtimeBaseUri()).toString(),
                headers,
                body,
                original.charset(),
                original.requestTemplate());
    }

    private Response fallbackResponse(Request original, FallbackResponse fallback) {
        Map<String, Collection<String>> headers = new LinkedHashMap<String, Collection<String>>();
        List<String> contentTypes = new ArrayList<String>();
        contentTypes.add(fallback.contentType());
        headers.put("Content-Type", contentTypes);
        return Response.builder()
                .status(fallback.status())
                .reason("Mock Runtime unavailable")
                .headers(headers)
                .body(fallback.body(), StandardCharsets.UTF_8)
                .request(original)
                .build();
    }
}
