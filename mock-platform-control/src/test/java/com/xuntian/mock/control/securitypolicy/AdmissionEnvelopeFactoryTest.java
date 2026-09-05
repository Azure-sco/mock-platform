package com.xuntian.mock.control.securitypolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.control.security.LocalPayloadSigner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionEnvelopeFactoryTest {

    @Test
    void signsCanonicalSixtySecondLeaseAndHashesTheEmbeddedPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = (ObjectNode) mapper.readTree("""
                {"rules":[],"appCode":"orders","environment":"DEV"}
                """);
        SecurityPolicyVersionRecord policy = new SecurityPolicyVersionRecord(
                21L, "policy-1", "APP_ACL", "DEV:orders", 1, "protected",
                "f".repeat(64), "PUBLISHED", "policy-signature", "policy-key",
                "SHA256withRSA", null, 7L, "author", Instant.EPOCH, "publisher", Instant.EPOCH);
        AdmissionEnvelopeFactory factory = new AdmissionEnvelopeFactory(new LocalPayloadSigner());
        Instant issuedAt = Instant.parse("2026-08-31T00:00:00Z");

        AdmissionEnvelopeFactory.PreparedEnvelope prepared = factory.create(
                "binding-1", policy, 3L, payload, issuedAt);

        ObjectNode envelope = (ObjectNode) mapper.readTree(prepared.canonicalBytes());
        assertThat(envelope.path("signatureAlgorithm").asText()).isEqualTo("SHA256withRSA");
        assertThat(envelope.path("policyChecksum").asText())
                .isEqualTo(Checksum.sha256Hex(CanonicalJson.write(payload)));
        assertThat(Duration.between(prepared.issuedAt(), prepared.notAfter())).isEqualTo(Duration.ofSeconds(60));
        ObjectNode content = envelope.deepCopy();
        content.remove(java.util.List.of("checksum", "signature", "signatureKeyId", "signatureAlgorithm"));
        assertThat(envelope.path("checksum").asText())
                .isEqualTo(Checksum.sha256Hex(CanonicalJson.write(content)));
        assertThat(prepared.canonicalBytesChecksum()).isEqualTo(Checksum.sha256Hex(prepared.canonicalBytes()));
    }
}
