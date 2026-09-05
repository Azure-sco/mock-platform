package com.xuntian.mock.client.core.routing;

import com.xuntian.mock.client.core.model.MockMode;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class RoutingSnapshot {

    private final long configVersion;
    private final String appCode;
    private final String environment;
    private final URI runtimeBaseUri;
    private final RouteConfig defaultRoute;
    private final Map<String, RouteConfig> providerRoutes;
    private final Map<String, RouteConfig> apiRoutes;
    private final boolean allowRequestOverride;
    private final boolean mockConfigValid;
    private final long observedConfigVersion;
    private final Instant mockConfigExpiresAt;

    private RoutingSnapshot(Builder builder) {
        this.configVersion = builder.configVersion;
        this.appCode = Objects.requireNonNull(builder.appCode, "appCode");
        this.environment = Objects.requireNonNull(builder.environment, "environment");
        this.runtimeBaseUri = Objects.requireNonNull(builder.runtimeBaseUri, "runtimeBaseUri");
        this.defaultRoute = Objects.requireNonNull(builder.defaultRoute, "defaultRoute");
        this.providerRoutes = Collections.unmodifiableMap(new HashMap<String, RouteConfig>(builder.providerRoutes));
        this.apiRoutes = Collections.unmodifiableMap(new HashMap<String, RouteConfig>(builder.apiRoutes));
        this.allowRequestOverride = builder.allowRequestOverride;
        this.mockConfigValid = builder.mockConfigValid;
        this.observedConfigVersion = Math.max(builder.configVersion, builder.observedConfigVersion);
        this.mockConfigExpiresAt = builder.mockConfigExpiresAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builderFrom(RoutingSnapshot snapshot) {
        return new Builder(snapshot);
    }

    public long configVersion() {
        return configVersion;
    }

    public String appCode() {
        return appCode;
    }

    public String environment() {
        return environment;
    }

    public URI runtimeBaseUri() {
        return runtimeBaseUri;
    }

    public boolean allowRequestOverride() {
        return allowRequestOverride;
    }

    /**
     * Returns whether MOCK/CANARY routes are backed by the last completely verified configuration.
     * REAL routes remain usable when this flag is false.
     */
    public boolean mockConfigValid() {
        return mockConfigValid;
    }

    public long observedConfigVersion() {
        return observedConfigVersion;
    }

    public Instant mockConfigExpiresAt() {
        return mockConfigExpiresAt;
    }

    public boolean containsOnlyRealRoutes() {
        if (defaultRoute.mode() != MockMode.REAL) {
            return false;
        }
        for (RouteConfig route : providerRoutes.values()) {
            if (route.mode() != MockMode.REAL) {
                return false;
            }
        }
        for (RouteConfig route : apiRoutes.values()) {
            if (route.mode() != MockMode.REAL) {
                return false;
            }
        }
        return true;
    }

    public RouteConfig route(String provider, String api) {
        RouteConfig apiRoute = apiRoutes.get(apiKey(provider, api));
        if (apiRoute != null) {
            return apiRoute;
        }
        RouteConfig providerRoute = providerRoutes.get(provider);
        return providerRoute == null ? defaultRoute : providerRoute;
    }

    private static String apiKey(String provider, String api) {
        return provider + '\u0000' + api;
    }

    public static final class Builder {
        private long configVersion;
        private String appCode;
        private String environment;
        private URI runtimeBaseUri;
        private RouteConfig defaultRoute = RouteConfig.real();
        private final Map<String, RouteConfig> providerRoutes = new HashMap<String, RouteConfig>();
        private final Map<String, RouteConfig> apiRoutes = new HashMap<String, RouteConfig>();
        private boolean allowRequestOverride;
        private boolean mockConfigValid = true;
        private long observedConfigVersion;
        private Instant mockConfigExpiresAt;

        private Builder() {
        }

        private Builder(RoutingSnapshot snapshot) {
            this.configVersion = snapshot.configVersion;
            this.appCode = snapshot.appCode;
            this.environment = snapshot.environment;
            this.runtimeBaseUri = snapshot.runtimeBaseUri;
            this.defaultRoute = snapshot.defaultRoute;
            this.providerRoutes.putAll(snapshot.providerRoutes);
            this.apiRoutes.putAll(snapshot.apiRoutes);
            this.allowRequestOverride = snapshot.allowRequestOverride;
            this.mockConfigValid = snapshot.mockConfigValid;
            this.observedConfigVersion = snapshot.observedConfigVersion;
            this.mockConfigExpiresAt = snapshot.mockConfigExpiresAt;
        }

        public Builder configVersion(long configVersion) {
            this.configVersion = configVersion;
            return this;
        }

        public Builder appCode(String appCode) {
            this.appCode = appCode;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder runtimeBaseUri(URI runtimeBaseUri) {
            this.runtimeBaseUri = runtimeBaseUri;
            return this;
        }

        public Builder defaultRoute(RouteConfig defaultRoute) {
            this.defaultRoute = defaultRoute;
            return this;
        }

        public Builder providerRoute(String provider, RouteConfig route) {
            this.providerRoutes.put(provider, route);
            return this;
        }

        public Builder apiRoute(String provider, String api, RouteConfig route) {
            this.apiRoutes.put(apiKey(provider, api), route);
            return this;
        }

        public Builder allowRequestOverride(boolean allowRequestOverride) {
            this.allowRequestOverride = allowRequestOverride;
            return this;
        }

        public Builder mockConfigValid(boolean mockConfigValid) {
            this.mockConfigValid = mockConfigValid;
            return this;
        }

        public Builder observedConfigVersion(long observedConfigVersion) {
            this.observedConfigVersion = observedConfigVersion;
            return this;
        }

        public Builder mockConfigExpiresAt(Instant mockConfigExpiresAt) {
            this.mockConfigExpiresAt = mockConfigExpiresAt;
            return this;
        }

        public RoutingSnapshot build() {
            return new RoutingSnapshot(this);
        }
    }
}
