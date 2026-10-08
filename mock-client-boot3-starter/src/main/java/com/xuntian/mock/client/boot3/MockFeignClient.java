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
import feign.Client;
import feign.Request;
import feign.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(MockFeignClient.class);
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
        if (current.isEmpty()) {
            return delegate.execute(original, options);
        }
        MockContext context = current.get();
        RouteDecision decision = routeResolver.resolve(configProvider.current(), context);
        LOG.debug(
                "Mock route decision mockRequestId={} provider={} api={} mode={}",
                logValue(context.mockRequestId()), logValue(context.provider()), logValue(context.api()),
                decision.mode());
        if (decision.mode() == MockMode.REAL) {
            return delegate.execute(original, options);
        }

        URI originalUri = URI.create(original.url());
        Request mockRequest = copyForMock(original, context, decision);
        try {
            return delegate.execute(mockRequest, options);
        } catch (IOException failure) {
            FailureAction action = MockFailurePolicy.decide(context, decision, originalUri, true, failure);
            LOG.warn(
                    "Mock Runtime request failed mockRequestId={} provider={} api={} action={} errorType={}",
                    logValue(context.mockRequestId()), logValue(context.provider()), logValue(context.api()), action,
                    failure.getClass().getSimpleName());
            if (action == FailureAction.FALLBACK_REAL) {
                return delegate.execute(original, options);
            }
            if (action == FailureAction.FALLBACK_RESPONSE) {
                return fallbackResponse(original, decision.routeConfig().fallbackResponse());
            }
            throw new MockRuntimeUnavailableException(context.mockRequestId(), failure);
        }
    }

    private static String logValue(String value) {
        String safe = value == null ? "" : value.replace('\r', '_').replace('\n', '_');
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

    private Request copyForMock(Request original, MockContext context, RouteDecision decision) {
        Map<String, List<String>> sanitized = MockHeaders.build(
                original.headers(), context, decision.routeConfig(), mockAppToken);
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        sanitized.forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
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
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(fallback.contentType()));
        return Response.builder()
                .status(fallback.status())
                .reason("Mock Runtime unavailable")
                .headers(headers)
                .body(fallback.body(), StandardCharsets.UTF_8)
                .request(original)
                .build();
    }
}
