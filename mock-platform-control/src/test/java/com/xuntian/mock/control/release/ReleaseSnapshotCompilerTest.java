package com.xuntian.mock.control.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReleaseSnapshotCompilerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CanonicalJsonCodec canonical = new CanonicalJsonCodec(mapper);

    @Test
    void createsExactCanonicalRuntimeEnvelopeAndRejectsTampering() throws Exception {
        ReleaseMapper releaseMapper = mock(ReleaseMapper.class);
        ReleaseSecurityPolicyGate gate = mock(ReleaseSecurityPolicyGate.class);
        LocalRsaRuntimeSnapshotSigner signer = new LocalRsaRuntimeSnapshotSigner();
        ReleaseSourceRecord source = source();
        when(releaseMapper.selectReleaseSources(List.of(41L))).thenReturn(List.of(source));
        ReleaseSnapshotCompiler compiler = new ReleaseSnapshotCompiler(
                releaseMapper, mapper, canonical, signer, gate);

        ReleaseSnapshotCompiler.Selection selection = compiler.validateSelection("test", "sample", List.of(41L));
        ReleaseSnapshotCompiler.CompiledRelease result = compiler.compile(
                "rel-1", Instant.parse("2026-08-31T00:00:00Z"), selection);

        assertThat(result.checksum()).isEqualTo(Checksum.sha256Hex(result.canonicalSnapshotBytes()));
        assertThat(result.envelope().signatureAlgorithm()).isEqualTo("SHA256withRSA");
        assertThat(result.snapshot().schemaVersion()).isEqualTo("2");
        assertThat(result.snapshot().compiledArtifacts()).isEqualTo(
                new ReleaseSnapshotCompiler.CompiledArtifactManifest(
                        "contract-v1", "matcher-v1", "template-v1", "flow-v1"));
        assertThat(result.snapshot().compiledContracts()).hasSize(1);
        assertThat(result.snapshot().compiledScenarios()).hasSize(1);
        assertThat(result.snapshot().flowDefinitions()).isEmpty();
        compiler.verifyEnvelope(result.canonicalEnvelopeBytes(), "rel-1", result.checksum());

        ObjectNode tampered = (ObjectNode) canonical.read(result.canonicalEnvelopeBytes()).deepCopy();
        ((ObjectNode) tampered.path("snapshot")).put("app", "other-app");
        assertThatThrownBy(() -> compiler.verifyEnvelope(
                canonical.write(tampered), "rel-1", result.checksum()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("checksum");
    }

    private ReleaseSourceRecord source() throws Exception {
        String scope = """
                {"environments":["TEST"],"apps":["sample"],"tenants":[],"testAccounts":[]}
                """;
        String rules = "[]";
        String response = """
                {"httpStatus":200,"headers":{"Content-Type":"application/json"},
                 "bodyTemplate":"{\\\"ok\\\":true}","variableDefaults":{}}
                """;
        String callbacks = "[]";
        String compiled = """
                {"scope":{"environments":["TEST"],"apps":["sample"],"tenants":[],"testAccounts":[]},
                 "matchRules":[],"response":{"httpStatus":200,"headers":{"Content-Type":"application/json"},
                 "bodyTemplate":"{\\\"ok\\\":true}","variableDefaults":{}},"validatorVersion":"scenario-v1"}
                """;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("contractVersionId", 31L);
        content.put("flowDefinitionVersionId", null);
        content.put("priority", 100);
        content.put("effectiveFrom", null);
        content.put("effectiveTo", null);
        content.put("scope", mapper.convertValue(mapper.readTree(scope), Object.class));
        content.put("matchRules", mapper.convertValue(mapper.readTree(rules), Object.class));
        content.put("response", mapper.convertValue(mapper.readTree(response), Object.class));
        content.put("callbacks", mapper.convertValue(mapper.readTree(callbacks), Object.class));
        String checksum = Checksum.sha256Hex(CanonicalJson.write(content));
        return new ReleaseSourceRecord(
                41L, 21L, "success", "APPROVED", "ENABLED",
                31L, "PUBLISHED", null, 100, null, null,
                scope, rules, response, callbacks, compiled, checksum, "VALID",
                "esign", "ENABLED", "contract.query", 11L, "ENABLED", "POST", "/contracts/query",
                "application/json", "{\"type\":\"object\"}", "{\"type\":\"object\"}", null);
    }
}
