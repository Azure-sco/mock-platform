package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompiledMatchRuleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void supportsAllM1RuleSourcesWithAndFriendlyPredicates() throws Exception {
        RuntimeRequest request = new RuntimeRequest(
                "TEST", "app", null, null, "P", "A", "POST", "/path", "application/json",
                Map.of("X-Mode", List.of("FAST")),
                Map.of("channel", List.of("EQB")),
                "{\"data\":{\"state\":\"SIGNED\"}}".getBytes(), "mr", "trace");
        JsonNode body = mapper.readTree(request.body());

        assertThat(new CompiledMatchRule("HEADER", "EQ", "X-Mode", "fast", false)
                .matches(request, body, "BIZ-42")).isTrue();
        assertThat(new CompiledMatchRule("QUERY", "PREFIX", "channel", "E", true)
                .matches(request, body, "BIZ-42")).isTrue();
        assertThat(new CompiledMatchRule("JSON_PATH", "EQ", "$.data.state", "SIGNED", true)
                .matches(request, body, "BIZ-42")).isTrue();
        assertThat(new CompiledMatchRule("BUSINESS_NO", "CONTAINS", "ignored", "42", true)
                .matches(request, body, "BIZ-42")).isTrue();
        assertThat(new CompiledMatchRule("REGEX", "REGEX", "BUSINESS_NO:value", "BIZ-[0-9]+", true)
                .matches(request, body, "BIZ-42")).isTrue();
    }

    @Test
    void re2jRejectsBacktrackingOnlyConstructs() {
        assertThatThrownBy(() -> new CompiledMatchRule(
                "REGEX", "REGEX", "HEADER:X-Test", "^(a+)+(?=b)$", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompiledMatchRule(
                "HEADER", "EQ", "Authorization", "MockApp secret", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
