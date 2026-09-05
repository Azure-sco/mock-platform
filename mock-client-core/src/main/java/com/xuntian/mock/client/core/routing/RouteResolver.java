package com.xuntian.mock.client.core.routing;

import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.failure.MockConfigInvalidException;
import com.xuntian.mock.client.core.model.MockMode;

import java.time.Clock;
import java.util.Objects;

public final class RouteResolver {

    private final Clock clock;

    public RouteResolver() {
        this(Clock.systemUTC());
    }

    public RouteResolver(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RouteDecision resolve(RoutingSnapshot snapshot, MockContext context) {
        RouteConfig route = snapshot.route(context.provider(), context.api());
        if (isProduction(snapshot.environment()) || isProduction(context.environment())) {
            return new RouteDecision(MockMode.REAL, snapshot, route);
        }
        if (route.mode() != MockMode.REAL
                && (!snapshot.mockConfigValid()
                || (snapshot.mockConfigExpiresAt() != null
                && !clock.instant().isBefore(snapshot.mockConfigExpiresAt())))) {
            throw new MockConfigInvalidException(snapshot.configVersion(), snapshot.observedConfigVersion());
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
