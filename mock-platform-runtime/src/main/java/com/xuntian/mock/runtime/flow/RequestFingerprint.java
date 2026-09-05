package com.xuntian.mock.runtime.flow;

import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.runtime.engine.RuntimeRequest;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RequestFingerprint {

    private RequestFingerprint() { }

    public static String calculate(RuntimeRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("environment", request.environment());
        value.put("app", request.app());
        value.put("tenant", request.tenant());
        value.put("testAccount", request.testAccount());
        value.put("provider", request.provider());
        value.put("api", request.api());
        value.put("method", request.method());
        value.put("path", request.rawPath());
        value.put("contentType", request.contentType());
        value.put("headers", request.headers());
        value.put("query", request.query());
        value.put("bodySha256", Checksum.sha256Hex(request.body()));
        value.put("explicitScenario", request.firstHeader("X-Mock-Explicit-Scenario").orElse(null));
        return Checksum.sha256Hex(CanonicalJson.write(value));
    }
}
