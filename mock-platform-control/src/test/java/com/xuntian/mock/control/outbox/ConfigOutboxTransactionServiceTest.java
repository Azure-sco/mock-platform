package com.xuntian.mock.control.outbox;

import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.sdkconfig.ActiveSdkConfigRecord;
import com.xuntian.mock.control.sdkconfig.SdkConfigActivationRecord;
import com.xuntian.mock.control.sdkconfig.SdkConfigMapper;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigOutboxTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void claimIncrementsFenceAgainstTheLockedCandidateToken() {
        ConfigPublishOutboxMapper outboxMapper = mock(ConfigPublishOutboxMapper.class);
        ConfigPublishOutboxRecord candidate = outbox(4L, null);
        ConfigPublishOutboxRecord claimed = outbox(5L, "worker-1");
        when(outboxMapper.lockNextClaimable(NOW, 8)).thenReturn(candidate);
        when(outboxMapper.claim(41L, "worker-1", NOW.plusSeconds(30), 4L, NOW)).thenReturn(1);
        when(outboxMapper.selectById(41L)).thenReturn(claimed);
        ConfigOutboxTransactionService service = service(outboxMapper, mock(SdkConfigMapper.class));

        ConfigPublishOutboxRecord result = service.claim("worker-1", Duration.ofSeconds(30));

        assertThat(result.fencingToken()).isEqualTo(5L);
        verify(outboxMapper).claim(41L, "worker-1", NOW.plusSeconds(30), 4L, NOW);
    }

    @Test
    void oldWorkerCannotFinalizeAfterAReclaim() {
        ConfigPublishOutboxMapper outboxMapper = mock(ConfigPublishOutboxMapper.class);
        SdkConfigMapper sdkMapper = mock(SdkConfigMapper.class);
        ConfigPublishOutboxRecord hint = outbox(7L, "old-worker");
        ConfigPublishOutboxRecord reclaimed = outbox(8L, "new-worker");
        SdkConfigActivationRecord activation = new SdkConfigActivationRecord(
                "activation-1", "orders", "DEV", 9L, 1L, 2L,
                "PENDING", "request-1", "admin", NOW, null);
        when(outboxMapper.selectById(41L)).thenReturn(hint);
        when(outboxMapper.lockById(41L)).thenReturn(reclaimed);
        when(sdkMapper.selectActivation("activation-1")).thenReturn(activation);
        when(sdkMapper.lockActivation("activation-1")).thenReturn(activation);
        when(sdkMapper.lockActive("orders", "DEV")).thenReturn(new ActiveSdkConfigRecord(
                "orders", "DEV", 9L, 2L, 8L, 1L,
                "activation-1", "ACTIVATING", NOW));
        ConfigOutboxTransactionService service = service(outboxMapper, sdkMapper);

        assertThatThrownBy(() -> service.finish(41L, "old-worker", 7L, true))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("fenced");

        verify(outboxMapper, never()).markPublished(anyLong(), anyString(), anyLong(), any());
        verify(sdkMapper, never()).markActivationProjected(anyString());
    }

    private ConfigOutboxTransactionService service(
            ConfigPublishOutboxMapper outboxMapper,
            SdkConfigMapper sdkMapper) {
        return new ConfigOutboxTransactionService(
                outboxMapper, mock(SecurityPolicyMapper.class), sdkMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ConfigPublishOutboxRecord outbox(long fence, String owner) {
        return new ConfigPublishOutboxRecord(
                41L, "SDK_CONFIG_ACTIVATION", "activation-1", "APOLLO", "orders-dev",
                "protected-wrapper", "0".repeat(64), "NEW", 1, NOW, null,
                owner, NOW.plusSeconds(30), fence, NOW, null);
    }
}
