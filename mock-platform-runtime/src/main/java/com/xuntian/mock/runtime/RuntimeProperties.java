package com.xuntian.mock.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties("xuntian.mock.runtime")
public class RuntimeProperties {

    private String environment = "TEST";
    private Map<String, String> localAppTokens = new LinkedHashMap<>();

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Map<String, String> getLocalAppTokens() {
        return localAppTokens;
    }

    public void setLocalAppTokens(Map<String, String> localAppTokens) {
        this.localAppTokens = localAppTokens;
    }
}
