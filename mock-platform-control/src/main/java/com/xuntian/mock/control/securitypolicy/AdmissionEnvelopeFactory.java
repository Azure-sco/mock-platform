package com.xuntian.mock.control.securitypolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.control.security.PayloadSigner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public final class AdmissionEnvelopeFactory {

    private final PayloadSigner payloadSigner;

    public AdmissionEnvelopeFactory(PayloadSigner payloadSigner) {
        this.payloadSigner = payloadSigner;
    }

    public PreparedEnvelope create(
            String bindingId,
            SecurityPolicyVersionRecord policy,
            long bindingVersion,
            JsonNode config,
            Instant issuedAt) {
        Instant notAfter = issuedAt.plusSeconds(60);
        String environment = config.path("environment").asText();
        String appCode = config.path("appCode").asText();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("bindingId", bindingId);
        content.put("environment", environment);
        content.put("appCode", appCode);
        content.put("policyVersionId", policy.id());
        content.put("bindingVersion", bindingVersion);
        content.put("payload", config);
        content.put("policyChecksum", Checksum.sha256Hex(CanonicalJson.write(config)));
        content.put("issuedAt", issuedAt.toString());
        content.put("notAfter", notAfter.toString());
        byte[] contentBytes = CanonicalJson.write(content);
        String envelopeChecksum = Checksum.sha256Hex(contentBytes);
        PayloadSigner.SignatureValue signature = payloadSigner.sign(contentBytes);
        Map<String, Object> envelope = new LinkedHashMap<>(content);
        envelope.put("checksum", envelopeChecksum);
        envelope.put("signature", signature.signature());
        envelope.put("signatureKeyId", signature.keyId());
        envelope.put("signatureAlgorithm", signature.algorithm());
        byte[] canonicalEnvelope = CanonicalJson.write(envelope);
        return new PreparedEnvelope(
                environment, appCode, issuedAt, notAfter, canonicalEnvelope,
                Checksum.sha256Hex(canonicalEnvelope));
    }

    public record PreparedEnvelope(
            String environment,
            String appCode,
            Instant issuedAt,
            Instant notAfter,
            byte[] canonicalBytes,
            String canonicalBytesChecksum) {
    }
}
