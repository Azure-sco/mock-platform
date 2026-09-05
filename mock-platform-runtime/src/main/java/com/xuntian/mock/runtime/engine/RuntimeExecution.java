package com.xuntian.mock.runtime.engine;

import java.util.Map;

public record RuntimeExecution(
        int status,
        Map<String, String> headers,
        byte[] body,
        String scenarioId,
        String scenarioVersionId,
        String releaseId,
        String businessNo,
        long delayMs,
        RuntimeFault fault) {

    public RuntimeExecution {
        headers = Map.copyOf(headers);
        body = body.clone();
        if (delayMs < 0 || delayMs > RuntimeFault.MAX_DURATION_MS) {
            throw new IllegalArgumentException("Runtime response delay is invalid");
        }
        fault = fault == null ? RuntimeFault.none() : fault;
    }

    public RuntimeExecution(
            int status,
            Map<String, String> headers,
            byte[] body,
            String scenarioId,
            String scenarioVersionId,
            String releaseId,
            String businessNo) {
        this(status, headers, body, scenarioId, scenarioVersionId, releaseId, businessNo,
                0, RuntimeFault.none());
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
