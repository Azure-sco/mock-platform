package com.xuntian.mock.runtime.dispatch;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class M0FixedResponseDispatcher {

    public FixedMockResponse dispatch(String provider, String api, String requestId) {
        Map<String, Object> data = switch (provider + ":" + api) {
            case "OA:OA_SETTLE_CREATE" -> mapOf(
                    "flowNo", "MOCK-OA-" + requestId,
                    "status", "SUBMITTED");
            case "OA:OA_NUMBER_QUERY" -> mapOf(
                    "flowNo", "MOCK-OA-" + requestId,
                    "oaNumber", "OA-M0-0001");
            case "CPS_EQB:CPS_SIGN_CREATE_START" -> mapOf(
                    "flowId", "MOCK-EQB-" + requestId,
                    "status", "SIGNING");
            case "CPS_EQB:CPS_FLOW_FILES" -> mapOf(
                    "flowId", "MOCK-EQB-" + requestId,
                    "status", "SIGNED",
                    "files", List.of(mapOf(
                            "fileId", "M0-FILE-1",
                            "fileName", "mock-signed.pdf")));
            default -> throw new PlatformException(
                    ErrorCode.MOCK_NO_FIXED_RESPONSE,
                    "No M0 fixed response for provider/api");
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "OA".equals(provider) ? "200" : "0");
        body.put("message", "success");
        body.put("source", "M0_FIXED");
        body.put("data", data);
        return new FixedMockResponse(200, "application/json", body);
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
