package com.xuntian.mock.client.core.failure;

import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.model.UnavailablePolicy;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RouteDecision;

import java.net.URI;

public final class MockFailurePolicy {

    private MockFailurePolicy() {
    }

    public static FailureAction decide(
            MockContext context,
            RouteDecision decision,
            URI originalUri,
            boolean replayable,
            Throwable failure) {
        RouteConfig route = decision.routeConfig();
        if (route.unavailablePolicy() == UnavailablePolicy.FALLBACK_RESPONSE
                && route.fallbackResponse() != null) {
            return FailureAction.FALLBACK_RESPONSE;
        }
        if (route.unavailablePolicy() == UnavailablePolicy.FALLBACK_REAL
                && FallbackDecider.mayFallbackReal(
                        context.environment(),
                        originalUri.getHost(),
                        route.allowedRealHosts(),
                        replayable,
                        FailureClassifier.classify(failure))) {
            return FailureAction.FALLBACK_REAL;
        }
        return FailureAction.FAST_FAIL;
    }
}
