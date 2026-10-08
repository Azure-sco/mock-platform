package com.xuntian.mock.runtime.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultipartPublishedSchemaTest {
    @Test
    void emptySchemaIsUnconstrainedButNonemptyJsonSchemaStillRequiresJsonContentType() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        try (var input = getClass().getResourceAsStream("/runtime-fixture.json")) {
            var definition = mapper.readTree(input);
            ObjectNode multipart = (ObjectNode) definition.path("contracts").get(0);
            multipart.set("requestSchema", mapper.createObjectNode());
            var compiler = new RuntimeSnapshotCompiler(mapper);
            assertThat(compiler.compile(mapper.treeToValue(definition, FixtureDefinition.class))).isNotEmpty();
            multipart.set("requestSchema", mapper.createObjectNode().put("type", "object"));
            assertThatThrownBy(() -> compiler.compile(mapper.treeToValue(definition, FixtureDefinition.class)))
                    .hasMessageContaining("JSON request schema requires application/json");
        }
    }
}
