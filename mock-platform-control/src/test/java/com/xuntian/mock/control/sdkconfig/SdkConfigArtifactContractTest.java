package com.xuntian.mock.control.sdkconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.security.LocalPayloadSigner;
import com.xuntian.mock.control.security.LocalProtectedPayloadCodec;
import com.xuntian.mock.control.security.PayloadSigner;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SdkConfigArtifactContractTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void emitsNestedSnapshotAndActivationWithIndependentChecksums() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LocalProtectedPayloadCodec codec = new LocalProtectedPayloadCodec();
        LocalPayloadSigner signer = new LocalPayloadSigner();
        JsonNode routing = objectMapper.readTree("""
                {"apiRoutes":{},"allowRequestOverride":false,
                 "defaultRoute":{"additionalSensitiveHeaders":[],"allowedBusinessHeaders":[],
                   "allowedRealHosts":[],"mode":"REAL","unavailablePolicy":"FAST_FAIL"},
                 "providerRoutes":{},"runtimeBaseUri":"https://runtime.internal"}
                """);
        JsonNode refs = objectMapper.readTree("[]");
        JsonNode payloads = objectMapper.readTree("{}");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "1");
        snapshot.put("appCode", "orders");
        snapshot.put("environment", "DEV");
        snapshot.put("configVersion", 2L);
        snapshot.put("routing", routing);
        snapshot.put("securityPolicyRefs", refs);
        snapshot.put("securityPolicyPayloads", payloads);
        snapshot.put("effectiveAt", NOW.toString());
        snapshot.put("expireAt", null);
        byte[] snapshotBytes = CanonicalJson.write(snapshot);
        PayloadSigner.SignatureValue envelopeSignature = signer.sign(snapshotBytes);
        String envelopeChecksum = Checksum.sha256Hex(snapshotBytes);
        SdkConfigEnvelopeRecord envelope = new SdkConfigEnvelopeRecord(
                9L, "orders", "DEV", 2L, objectMapper.writeValueAsString(routing), "[]",
                codec.protect(CanonicalJson.write(payloads)), NOW, null, envelopeChecksum,
                envelopeSignature.signature(), envelopeSignature.keyId(), envelopeSignature.algorithm(),
                "VALID", "APPROVED", 77L, null, "author", NOW, null, null);
        SdkConfigMapper mapper = mock(SdkConfigMapper.class);
        SdkConfigTransactionService transactions = mock(SdkConfigTransactionService.class);
        SdkInstanceDiscoveryPort discovery = mock(SdkInstanceDiscoveryPort.class);
        ApprovalService approvals = mock(ApprovalService.class);
        when(mapper.selectEnvelope(9L)).thenReturn(envelope);
        when(mapper.selectActive("orders", "DEV")).thenReturn(null);
        when(discovery.available()).thenReturn(true);
        when(discovery.registeredReadyInstances("orders", "DEV")).thenReturn(Set.of("sdk-1"));
        SdkConfigService service = new SdkConfigService(
                mapper, new SdkConfigValidator(), transactions, mock(SecurityPolicyService.class),
                codec, signer, discovery, approvals, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));

        service.publish(
                9L, new SdkConfigService.PublishCommand(0L, "APOLLO", "orders-dev"),
                "request-1", new OperatorContext("admin", Set.of("MOCK_ADMIN"), "trace-1"));

        ArgumentCaptor<SdkConfigTransactionService.PreparedActivation> capture =
                ArgumentCaptor.forClass(SdkConfigTransactionService.PreparedActivation.class);
        verify(transactions).publish(capture.capture(), any(OperatorContext.class));
        SdkConfigTransactionService.PreparedActivation prepared = capture.getValue();
        byte[] exactWrapper = codec.unprotect(prepared.protectedWrapper());
        JsonNode wrapper = objectMapper.readTree(exactWrapper);
        assertThat(iterable(wrapper.fieldNames())).containsExactlyInAnyOrder(
                "activation", "wrapperChecksum", "wrapperSignature",
                "wrapperSignatureKeyId", "wrapperSignatureAlgorithm");
        assertThat(wrapper.path("wrapperSignatureAlgorithm").asText()).isEqualTo("SHA256withRSA");
        JsonNode activation = wrapper.path("activation");
        assertThat(activation.path("schemaVersion").asText()).isEqualTo("1");
        assertThat(activation.path("envelopeId").asText()).isEqualTo("9");
        assertThat(wrapper.path("wrapperChecksum").asText())
                .isEqualTo(Checksum.sha256Hex(CanonicalJson.write(activation)));
        JsonNode signedEnvelope = activation.path("envelope");
        assertThat(iterable(signedEnvelope.fieldNames())).containsExactlyInAnyOrder(
                "snapshot", "checksum", "signature", "signatureKeyId", "signatureAlgorithm");
        assertThat(signedEnvelope.path("signatureAlgorithm").asText()).isEqualTo("SHA256withRSA");
        assertThat(signedEnvelope.path("checksum").asText())
                .isEqualTo(Checksum.sha256Hex(CanonicalJson.write(signedEnvelope.path("snapshot"))));
        assertThat(signedEnvelope.path("snapshot").has("id")).isFalse();
        assertThat(prepared.wrapperBytesChecksum()).isEqualTo(Checksum.sha256Hex(exactWrapper));
        verify(approvals).requireApproved(77L, SdkConfigApprovalHandler.OBJECT_TYPE, 9L, envelopeChecksum);
    }

    private List<String> iterable(java.util.Iterator<String> values) {
        List<String> result = new java.util.ArrayList<>();
        values.forEachRemaining(result::add);
        return result;
    }
}
