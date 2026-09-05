package com.xuntian.mock.runtime.requestlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.runtime.RuntimeProperties;
import com.xuntian.mock.runtime.engine.RuntimeExecution;
import com.xuntian.mock.runtime.engine.RuntimeRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLogEntryTest {

    @Test
    void storesOnlySafeBoundedMetadataAndNeverRequestBodyOrCredentials() throws Exception {
        RuntimeRequest request = new RuntimeRequest(
                "TEST", "app", null, "tester-1234", "P", "A", "POST", "/orders",
                "application/json; note=\"quoted\\value\"",
                Map.of(
                        "Authorization", List.of("MockApp top-secret"),
                        "Cookie", List.of("session=top-secret"),
                        "X-Business-Tag", List.of("settlement")),
                Map.of("businessNo", List.of("do-not-log")),
                "{\"card\":\"4111111111111111\"}".getBytes(), "mr-1", "trace-1");
        RuntimeExecution execution = new RuntimeExecution(
                200, Map.of(), "{\"ok\":true}".getBytes(), "sc-1", "scv-1", "rel-1", null);

        RequestLogEntry entry = RequestLogEntry.success(
                request, execution, 12, new RuntimeProperties(), Instant.EPOCH);

        assertThat(entry.testAccountMasked()).isEqualTo("t***4");
        assertThat(entry.requestSummary())
                .doesNotContain("top-secret", "4111111111111111", "do-not-log", "Authorization", "Cookie");
        assertThat(new ObjectMapper().readTree(entry.requestSummary()).path("bodyBytes").asInt())
                .isEqualTo(request.bodyLength());
    }
}
