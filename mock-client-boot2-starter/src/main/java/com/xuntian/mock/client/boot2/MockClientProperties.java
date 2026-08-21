package com.xuntian.mock.client.boot2;

import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.model.UnavailablePolicy;
import com.xuntian.mock.client.core.routing.FallbackResponse;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("xuntian.mock.client")
public class MockClientProperties {

    private String appCode = "application";
    private String environment = "LOCAL";
    private URI runtimeBaseUri = URI.create("http://localhost:19091");
    private MockMode mode = MockMode.REAL;
    private UnavailablePolicy unavailablePolicy = UnavailablePolicy.FAST_FAIL;
    private String mockAppToken;
    private String tenant;
    private String testAccount;
    private String traceId;
    private String businessNo;
    private boolean requestOverrideMock;
    private boolean allowRequestOverride;
    private int fallbackStatus = 503;
    private String fallbackContentType = "application/json";
    private String fallbackBody = "{\"code\":\"MOCK_RUNTIME_UNAVAILABLE\",\"message\":\"mock runtime unavailable\"}";
    private List<String> allowedBusinessHeaders = new ArrayList<String>();
    private List<String> additionalSensitiveHeaders = new ArrayList<String>();
    private List<String> allowedRealHosts = new ArrayList<String>();

    RoutingSnapshot toSnapshot() {
        RouteConfig.Builder route = RouteConfig.builder(mode).unavailablePolicy(unavailablePolicy);
        if (unavailablePolicy == UnavailablePolicy.FALLBACK_RESPONSE) {
            route.fallbackResponse(new FallbackResponse(fallbackStatus, fallbackContentType, fallbackBody));
        }
        for (String header : allowedBusinessHeaders) {
            route.allowBusinessHeader(header);
        }
        for (String header : additionalSensitiveHeaders) {
            route.denyHeader(header);
        }
        for (String host : allowedRealHosts) {
            route.allowRealHost(host);
        }
        return RoutingSnapshot.builder()
                .configVersion(1)
                .appCode(appCode)
                .environment(environment)
                .runtimeBaseUri(runtimeBaseUri)
                .defaultRoute(route.build())
                .allowRequestOverride(allowRequestOverride)
                .build();
    }

    public String getAppCode() {
        return appCode;
    }

    public void setAppCode(String appCode) {
        this.appCode = appCode;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public URI getRuntimeBaseUri() {
        return runtimeBaseUri;
    }

    public void setRuntimeBaseUri(URI runtimeBaseUri) {
        this.runtimeBaseUri = runtimeBaseUri;
    }

    public MockMode getMode() {
        return mode;
    }

    public void setMode(MockMode mode) {
        this.mode = mode;
    }

    public UnavailablePolicy getUnavailablePolicy() {
        return unavailablePolicy;
    }

    public void setUnavailablePolicy(UnavailablePolicy unavailablePolicy) {
        this.unavailablePolicy = unavailablePolicy;
    }

    public String getMockAppToken() {
        return mockAppToken;
    }

    public void setMockAppToken(String mockAppToken) {
        this.mockAppToken = mockAppToken;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getTestAccount() {
        return testAccount;
    }

    public void setTestAccount(String testAccount) {
        this.testAccount = testAccount;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getBusinessNo() {
        return businessNo;
    }

    public void setBusinessNo(String businessNo) {
        this.businessNo = businessNo;
    }

    public boolean isRequestOverrideMock() {
        return requestOverrideMock;
    }

    public void setRequestOverrideMock(boolean requestOverrideMock) {
        this.requestOverrideMock = requestOverrideMock;
    }

    public boolean isAllowRequestOverride() {
        return allowRequestOverride;
    }

    public void setAllowRequestOverride(boolean allowRequestOverride) {
        this.allowRequestOverride = allowRequestOverride;
    }

    public int getFallbackStatus() {
        return fallbackStatus;
    }

    public void setFallbackStatus(int fallbackStatus) {
        this.fallbackStatus = fallbackStatus;
    }

    public String getFallbackContentType() {
        return fallbackContentType;
    }

    public void setFallbackContentType(String fallbackContentType) {
        this.fallbackContentType = fallbackContentType;
    }

    public String getFallbackBody() {
        return fallbackBody;
    }

    public void setFallbackBody(String fallbackBody) {
        this.fallbackBody = fallbackBody;
    }

    public List<String> getAllowedBusinessHeaders() {
        return allowedBusinessHeaders;
    }

    public void setAllowedBusinessHeaders(List<String> allowedBusinessHeaders) {
        this.allowedBusinessHeaders = allowedBusinessHeaders;
    }

    public List<String> getAdditionalSensitiveHeaders() {
        return additionalSensitiveHeaders;
    }

    public void setAdditionalSensitiveHeaders(List<String> additionalSensitiveHeaders) {
        this.additionalSensitiveHeaders = additionalSensitiveHeaders;
    }

    public List<String> getAllowedRealHosts() {
        return allowedRealHosts;
    }

    public void setAllowedRealHosts(List<String> allowedRealHosts) {
        this.allowedRealHosts = allowedRealHosts;
    }
}
