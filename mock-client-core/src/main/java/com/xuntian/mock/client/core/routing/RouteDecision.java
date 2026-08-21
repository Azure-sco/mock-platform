package com.xuntian.mock.client.core.routing;

import com.xuntian.mock.client.core.model.MockMode;

import java.net.URI;

public final class RouteDecision {

    private final MockMode mode;
    private final long configVersion;
    private final URI runtimeBaseUri;
    private final RouteConfig routeConfig;

    RouteDecision(MockMode mode, RoutingSnapshot snapshot, RouteConfig routeConfig) {
        this.mode = mode;
        this.configVersion = snapshot.configVersion();
        this.runtimeBaseUri = snapshot.runtimeBaseUri();
        this.routeConfig = routeConfig;
    }

    public MockMode mode() {
        return mode;
    }

    public long configVersion() {
        return configVersion;
    }

    public URI runtimeBaseUri() {
        return runtimeBaseUri;
    }

    public RouteConfig routeConfig() {
        return routeConfig;
    }
}
