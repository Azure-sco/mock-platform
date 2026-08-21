package com.xuntian.mock.client.core.routing;

import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.model.UnavailablePolicy;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class RouteConfig {

    private final MockMode mode;
    private final UnavailablePolicy unavailablePolicy;
    private final FallbackResponse fallbackResponse;
    private final CanaryRule canaryRule;
    private final Set<String> allowedBusinessHeaders;
    private final Set<String> additionalSensitiveHeaders;
    private final Set<String> allowedRealHosts;

    private RouteConfig(Builder builder) {
        this.mode = Objects.requireNonNull(builder.mode, "mode");
        this.unavailablePolicy = Objects.requireNonNull(builder.unavailablePolicy, "unavailablePolicy");
        this.fallbackResponse = builder.fallbackResponse;
        this.canaryRule = builder.canaryRule;
        this.allowedBusinessHeaders = immutableCopy(builder.allowedBusinessHeaders);
        this.additionalSensitiveHeaders = immutableCopy(builder.additionalSensitiveHeaders);
        this.allowedRealHosts = immutableCopy(builder.allowedRealHosts);
    }

    public static RouteConfig real() {
        return builder(MockMode.REAL).build();
    }

    public static RouteConfig mock(UnavailablePolicy policy) {
        return builder(MockMode.MOCK).unavailablePolicy(policy).build();
    }

    public static RouteConfig canary(CanaryRule canaryRule, UnavailablePolicy policy) {
        return builder(MockMode.CANARY).canaryRule(canaryRule).unavailablePolicy(policy).build();
    }

    public static Builder builder(MockMode mode) {
        return new Builder(mode);
    }

    public MockMode mode() {
        return mode;
    }

    public UnavailablePolicy unavailablePolicy() {
        return unavailablePolicy;
    }

    public FallbackResponse fallbackResponse() {
        return fallbackResponse;
    }

    public CanaryRule canaryRule() {
        return canaryRule;
    }

    public Set<String> allowedBusinessHeaders() {
        return allowedBusinessHeaders;
    }

    public Set<String> additionalSensitiveHeaders() {
        return additionalSensitiveHeaders;
    }

    public Set<String> allowedRealHosts() {
        return allowedRealHosts;
    }

    private static Set<String> immutableCopy(Set<String> source) {
        return Collections.unmodifiableSet(new HashSet<String>(source));
    }

    public static final class Builder {
        private final MockMode mode;
        private UnavailablePolicy unavailablePolicy = UnavailablePolicy.FAST_FAIL;
        private FallbackResponse fallbackResponse;
        private CanaryRule canaryRule = CanaryRule.builder().build();
        private final Set<String> allowedBusinessHeaders = new HashSet<String>();
        private final Set<String> additionalSensitiveHeaders = new HashSet<String>();
        private final Set<String> allowedRealHosts = new HashSet<String>();

        private Builder(MockMode mode) {
            this.mode = mode;
        }

        public Builder unavailablePolicy(UnavailablePolicy unavailablePolicy) {
            this.unavailablePolicy = unavailablePolicy;
            return this;
        }

        public Builder fallbackResponse(FallbackResponse fallbackResponse) {
            this.fallbackResponse = fallbackResponse;
            return this;
        }

        public Builder canaryRule(CanaryRule canaryRule) {
            this.canaryRule = canaryRule;
            return this;
        }

        public Builder allowBusinessHeader(String header) {
            this.allowedBusinessHeaders.add(header);
            return this;
        }

        public Builder denyHeader(String header) {
            this.additionalSensitiveHeaders.add(header);
            return this;
        }

        public Builder allowRealHost(String host) {
            this.allowedRealHosts.add(host);
            return this;
        }

        public RouteConfig build() {
            return new RouteConfig(this);
        }
    }
}
