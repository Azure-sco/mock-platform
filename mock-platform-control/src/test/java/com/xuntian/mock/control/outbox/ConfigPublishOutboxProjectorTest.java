package com.xuntian.mock.control.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.control.sdkconfig.ActiveSdkConfigRecord;
import com.xuntian.mock.control.sdkconfig.SdkConfigActivationRecord;
import com.xuntian.mock.control.sdkconfig.SdkConfigMapper;
import com.xuntian.mock.control.security.ProtectedPayloadCodec;
import com.xuntian.mock.control.securitypolicy.AdmissionLeasePublisher;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigPublishOutboxProjectorTest {

    @Test
    void publishesTheExactPersistedWrapperBytesWithoutResigning() {
        byte[] exact = ("{\"activation\":{\"activationId\":\"activation-1\",\"configVersion\":2,"
                + "\"schemaVersion\":\"1\"},\"wrapperChecksum\":\"fixture\"}")
                .getBytes(StandardCharsets.UTF_8);
        ConfigPublishOutboxRecord outbox = outbox(exact, Checksum.sha256Hex(exact));
        ConfigOutboxTransactionService transactions = mock(ConfigOutboxTransactionService.class);
        ConfigPublisherAdapter publisher = mock(ConfigPublisherAdapter.class);
        AdmissionLeasePublisher admission = mock(AdmissionLeasePublisher.class);
        ProtectedPayloadCodec codec = mock(ProtectedPayloadCodec.class);
        SecurityPolicyMapper securityMapper = mock(SecurityPolicyMapper.class);
        SdkConfigMapper sdkMapper = mock(SdkConfigMapper.class);
        when(transactions.claim(anyString(), any())).thenReturn(outbox);
        when(codec.unprotect("protected-wrapper")).thenReturn(exact);
        SdkConfigActivationRecord activation = new SdkConfigActivationRecord(
                "activation-1", "orders", "DEV", 9L, 1L, 2L,
                "PENDING", "request-1", "admin", Instant.EPOCH, null);
        when(sdkMapper.selectActivation("activation-1")).thenReturn(activation);
        when(sdkMapper.selectActive("orders", "DEV")).thenReturn(new ActiveSdkConfigRecord(
                "orders", "DEV", 9L, 2L, 8L, 1L,
                "activation-1", "ACTIVATING", Instant.EPOCH));
        ConfigPublishOutboxProjector projector = new ConfigPublishOutboxProjector(
                transactions, publisher, admission, codec, securityMapper, sdkMapper, new ObjectMapper());

        projector.projectOne();

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(publisher).publish(eq("APOLLO"), eq("orders-dev"), eq("activation-1"),
                bytes.capture(), eq(Checksum.sha256Hex(exact)));
        assertThat(bytes.getValue()).containsExactly(exact);
        verify(transactions).finish(eq(41L), anyString(), eq(7L), eq(true));
        verify(transactions, never()).fail(eq(41L), anyString(), eq(7L), any());
    }

    @Test
    void checksumTamperingFailsBeforeExternalPublish() {
        byte[] exact = "{}".getBytes(StandardCharsets.UTF_8);
        ConfigPublishOutboxRecord outbox = outbox(exact, "0".repeat(64));
        ConfigOutboxTransactionService transactions = mock(ConfigOutboxTransactionService.class);
        ConfigPublisherAdapter publisher = mock(ConfigPublisherAdapter.class);
        ProtectedPayloadCodec codec = mock(ProtectedPayloadCodec.class);
        when(transactions.claim(anyString(), any())).thenReturn(outbox);
        when(codec.unprotect("protected-wrapper")).thenReturn(exact);
        ConfigPublishOutboxProjector projector = new ConfigPublishOutboxProjector(
                transactions, publisher, mock(AdmissionLeasePublisher.class), codec,
                mock(SecurityPolicyMapper.class), mock(SdkConfigMapper.class), new ObjectMapper());

        projector.projectOne();

        verify(publisher, never()).publish(anyString(), anyString(), anyString(), any(), anyString());
        verify(transactions).fail(eq(41L), anyString(), eq(7L), any());
    }

    private ConfigPublishOutboxRecord outbox(byte[] bytes, String checksum) {
        return new ConfigPublishOutboxRecord(
                41L, "SDK_CONFIG_ACTIVATION", "activation-1", "APOLLO", "orders-dev",
                "protected-wrapper", checksum, "NEW", 1, Instant.EPOCH, null,
                "worker-fixture", Instant.EPOCH.plusSeconds(30), 7L, Instant.EPOCH, null);
    }
}
