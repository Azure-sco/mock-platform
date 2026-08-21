package com.xuntian.mock.client.core.routing;

import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.model.MockMode;

public final class RouteResolver {

    public RouteDecision resolve(RoutingSnapshot snapshot, MockContext context) {
        RouteConfig route = snapshot.route(context.provider(), context.api());
        if (isProduction(snapshot.environment()) || isProduction(context.environment())) {
            return new RouteDecision(MockMode.REAL, snapshot, route);
        }
        if (route.mode() == MockMode.CANARY) {
            boolean matched = route.canaryRule().matches(context)
                    || (snapshot.allowRequestOverride() && context.requestOverrideMock());
            return new RouteDecision(matched ? MockMode.MOCK : MockMode.REAL, snapshot, route);
        }
        return new RouteDecision(route.mode(), snapshot, route);
    }

    private boolean isProduction(String environment) {
        return "PROD".equalsIgnoreCase(environment) || "PRODUCTION".equalsIgnoreCase(environment);
    }
}
