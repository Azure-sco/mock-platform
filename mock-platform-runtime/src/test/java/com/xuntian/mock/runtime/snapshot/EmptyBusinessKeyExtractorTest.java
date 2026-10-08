package com.xuntian.mock.runtime.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmptyBusinessKeyExtractorTest {
    @Test
    void acceptsAbsentOptionalExtractorIncludingPreviouslyPublishedEmptyObject() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        try (var input = getClass().getResourceAsStream("/runtime-fixture.json")) {
            var definition = mapper.readTree(input);
            var contract = (ObjectNode) definition.path("contracts").get(0);
            var compiler = new RuntimeSnapshotCompiler(mapper);
            for (String json : new String[]{"{}",
                    "{\"source\":null,\"path\":null,\"required\":false,\"normalize\":null}"}) {
                contract.set("businessKeyExtractor", mapper.readTree(json));
                assertThat(compiler.compile(mapper.treeToValue(definition, FixtureDefinition.class))).isNotEmpty();
            }
            for (String json : new String[]{"{\"required\":true}", "{\"path\":\"$.id\"}",
                    "{\"source\":\"JSON_BODY\"}", "{\"normalize\":\"TRIM\"}"}) {
                contract.set("businessKeyExtractor", mapper.readTree(json));
                assertThatThrownBy(() -> compiler.compile(mapper.treeToValue(definition, FixtureDefinition.class)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
}
