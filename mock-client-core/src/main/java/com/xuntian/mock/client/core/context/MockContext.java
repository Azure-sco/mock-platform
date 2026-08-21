package com.xuntian.mock.client.core.context;

import java.util.Objects;

public final class MockContext {

    private final String appCode;
    private final String environment;
    private final String provider;
    private final String api;
    private final String tenant;
    private final String testAccount;
    private final String traceId;
    private final String businessNo;
    private final String mockRequestId;
    private final boolean requestOverrideMock;

    private MockContext(Builder builder) {
        this.appCode = Objects.requireNonNull(builder.appCode, "appCode");
        this.environment = Objects.requireNonNull(builder.environment, "environment");
        this.provider = Objects.requireNonNull(builder.provider, "provider");
        this.api = Objects.requireNonNull(builder.api, "api");
        this.tenant = builder.tenant;
        this.testAccount = builder.testAccount;
        this.traceId = builder.traceId;
        this.businessNo = builder.businessNo;
        this.mockRequestId = Objects.requireNonNull(builder.mockRequestId, "mockRequestId");
        this.requestOverrideMock = builder.requestOverrideMock;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String appCode() {
        return appCode;
    }

    public String environment() {
        return environment;
    }

    public String provider() {
        return provider;
    }

    public String api() {
        return api;
    }

    public String tenant() {
        return tenant;
    }

    public String testAccount() {
        return testAccount;
    }

    public String traceId() {
        return traceId;
    }

    public String businessNo() {
        return businessNo;
    }

    public String mockRequestId() {
        return mockRequestId;
    }

    public boolean requestOverrideMock() {
        return requestOverrideMock;
    }

    public static final class Builder {
        private String appCode;
        private String environment;
        private String provider;
        private String api;
        private String tenant;
        private String testAccount;
        private String traceId;
        private String businessNo;
        private String mockRequestId;
        private boolean requestOverrideMock;

        private Builder() {
        }

        public Builder appCode(String appCode) {
            this.appCode = appCode;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder api(String api) {
            this.api = api;
            return this;
        }

        public Builder tenant(String tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder testAccount(String testAccount) {
            this.testAccount = testAccount;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder businessNo(String businessNo) {
            this.businessNo = businessNo;
            return this;
        }

        public Builder mockRequestId(String mockRequestId) {
            this.mockRequestId = mockRequestId;
            return this;
        }

        public Builder requestOverrideMock(boolean requestOverrideMock) {
            this.requestOverrideMock = requestOverrideMock;
            return this;
        }

        public MockContext build() {
            return new MockContext(this);
        }
    }
}
