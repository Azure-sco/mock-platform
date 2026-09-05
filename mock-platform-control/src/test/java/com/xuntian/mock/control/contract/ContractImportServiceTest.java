package com.xuntian.mock.control.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractImportServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContractImportService importer = new ContractImportService();

    @AfterEach
    void closeImporter() {
        importer.shutdown();
    }

    @Test
    void importsSelectedOpenApiOperationAndResolvesLocalSchemas() {
        String document = """
                {
                  "openapi":"3.0.3",
                  "paths":{
                    "/pets":{
                      "post":{
                        "requestBody":{"content":{"application/json":{
                          "schema":{"$ref":"#/components/schemas/PetRequest"},
                          "example":{"name":"Mochi"}
                        }}},
                        "responses":{"201":{"content":{"application/json":{
                          "schema":{"$ref":"#/components/schemas/PetResponse"},
                          "example":{"id":7}
                        }}}}
                      }
                    }
                  },
                  "components":{"schemas":{
                    "PetRequest":{"type":"object","properties":{"name":{"type":"string"}}},
                    "PetResponse":{"type":"object","properties":{"id":{"type":"integer"}}}
                  }}
                }
                """;

        ContractImportService.ParsedContract parsed = importer.parse(
                bytes(document),
                "pet-openapi.json",
                new ContractImportService.ImportOptions("/pets", "POST", null));

        assertThat(parsed.sourceType()).isEqualTo("OPENAPI");
        assertThat(parsed.sourceFileHash()).matches("[a-f0-9]{64}");
        assertThat(parsed.requestSchema().at("/properties/name/type").asText()).isEqualTo("string");
        assertThat(parsed.responseSchema().at("/properties/id/type").asText()).isEqualTo("integer");
        assertThat(parsed.requestSchema().findValue("$ref")).isNull();
        assertThat(parsed.examples().path("request").path("name").asText()).isEqualTo("Mochi");
        assertThat(parsed.examples().path("response").path("id").asInt()).isEqualTo(7);
    }

    @Test
    void importsSingleOperationOpenApiYamlWithoutSelection() {
        String document = """
                openapi: 3.0.3
                paths:
                  /health:
                    get:
                      responses:
                        '200':
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  status:
                                    type: string
                """;

        ContractImportService.ParsedContract parsed = importer.parse(
                bytes(document), "health.yaml", new ContractImportService.ImportOptions(null, null, null));

        assertThat(parsed.requestSchema().path("type").asText()).isEqualTo("object");
        assertThat(parsed.responseSchema().at("/properties/status/type").asText()).isEqualTo("string");
    }

    @Test
    void importsJsonSchemaIntoRequestedSide() throws Exception {
        JsonNode schema = objectMapper.readTree("""
                {"$schema":"http://json-schema.org/draft-07/schema#",
                 "type":"object","properties":{"ok":{"type":"boolean"}}}
                """);

        ContractImportService.ParsedContract parsed = importer.parse(
                bytes(schema.toString()),
                "response.schema.json",
                new ContractImportService.ImportOptions(null, null, "RESPONSE"));

        assertThat(parsed.sourceType()).isEqualTo("JSON_SCHEMA");
        assertThat(parsed.requestSchema().path("type").asText()).isEqualTo("object");
        assertThat(parsed.responseSchema().at("/properties/ok/type").asText()).isEqualTo("boolean");
    }

    @Test
    void rejectsExternalReferencesAnywhereInDocument() {
        String document = """
                {"openapi":"3.0.3","paths":{"/ok":{"get":{"responses":{"200":{}}}}},
                 "components":{"schemas":{"Hidden":{"$ref":"https://attacker.invalid/schema.json"}}}}
                """;

        assertInvalid(
                () -> importer.parse(bytes(document), "external.json", null),
                "External $ref is not allowed");
    }

    @Test
    void rejectsYamlAnchorsAndAliasesBeforeParsing() {
        String aliasBomb = """
                openapi: 3.0.3
                shared: &shared
                  value: x
                copies: [*shared, *shared]
                paths: {}
                """;

        assertInvalid(
                () -> importer.parse(bytes(aliasBomb), "alias.yaml", null),
                "YAML anchors and aliases are not allowed");
    }

    @Test
    void rejectsReferencesDeeperThanThirtyTwo() {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode definitions = root.putObject("definitions");
        for (int index = 0; index < 34; index++) {
            definitions.putObject("n" + index).put("$ref", "#/definitions/n" + (index + 1));
        }
        definitions.putObject("n34").put("type", "string");
        root.put("$ref", "#/definitions/n0");

        assertInvalid(
                () -> importer.parse(bytes(root.toString()), "deep-ref.json", null),
                "Local $ref exceeds depth 32");
    }

    @Test
    void rejectsDeeplyNestedJson() {
        StringBuilder document = new StringBuilder();
        for (int index = 0; index < 66; index++) document.append("{\"v\":");
        document.append("0");
        for (int index = 0; index < 66; index++) document.append('}');

        assertThatThrownBy(() -> importer.parse(bytes(document.toString()), "deep.json", null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsReferenceExpansionBeyondNodeLimit() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode shared = root.putObject("definitions").putObject("shared");
        shared.put("type", "object");
        for (int index = 0; index < 600; index++) {
            shared.putObject("field" + index).put("type", "string");
        }
        ObjectNode properties = root.putObject("properties");
        for (int index = 0; index < 200; index++) {
            properties.putObject("copy" + index).put("$ref", "#/definitions/shared");
        }

        assertInvalid(
                () -> importer.parse(bytes(root.toString()), "expansion.json", null),
                "Expanded contract exceeds 100000 nodes");
    }

    @Test
    void rejectsCompressedAndOversizedFiles() {
        assertInvalid(
                () -> importer.parse(new byte[]{0x1f, (byte) 0x8b, 0, 0}, "schema.json", null),
                "Compressed contract files are not allowed");

        byte[] oversized = new byte[ContractImportService.MAX_FILE_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        assertThatThrownBy(() -> importer.parse(oversized, "schema.json", null))
                .isInstanceOfSatisfying(PlatformException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    void requiresExplicitSelectionWhenOpenApiHasMultipleOperations() {
        String document = """
                {"openapi":"3.0.3","paths":{
                  "/a":{"get":{"responses":{"200":{}}}},
                  "/b":{"post":{"responses":{"200":{}}}}
                }}
                """;

        assertInvalid(
                () -> importer.parse(bytes(document), "multiple.json", null),
                "path and method are required");
    }

    private void assertInvalid(ThrowingCall call, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(PlatformException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(failure).hasMessageContaining(message);
                });
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
