package com.xuntian.mock.control.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.audit.AuditMapper;
import com.xuntian.mock.control.identity.OperatorContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleaseTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void expectedActivationVersionPreventsConcurrentOverwrite() {
        ReleaseMapper mapper = mock(ReleaseMapper.class);
        ReleaseTransactionService service = service(mapper);
        when(mapper.selectRelease("rel-2")).thenReturn(release());
        when(mapper.lockActive("TEST", "sample")).thenReturn(
                new ActiveReleaseRecord("TEST", "sample", "rel-1", 7L, "APPLIED", NOW));

        assertThatThrownBy(() -> service.activate(
                "act-2", "PUBLISH", "rel-2", 6L, List.of(),
                new OperatorContext("admin", Set.of("MOCK_ADMIN"), "req-activate")))
                .isInstanceOf(PlatformException.class)
                .extracting(failure -> ((PlatformException) failure).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(mapper, never()).activate(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleOutboxWorkerCannotFinalizeAfterFenceChanges() {
        ReleaseMapper mapper = mock(ReleaseMapper.class);
        ReleaseTransactionService service = service(mapper);
        ReleaseOutboxRecord initial = outbox("worker-new", 4L);
        ReleaseActivationRecord activation = activation();
        when(mapper.selectOutbox(9L)).thenReturn(initial);
        when(mapper.selectActivation("act-2")).thenReturn(activation);
        when(mapper.lockActive("TEST", "sample")).thenReturn(
                new ActiveReleaseRecord("TEST", "sample", "rel-2", 8L, "ACTIVATING", NOW));
        when(mapper.lockActivation("act-2")).thenReturn(activation);
        when(mapper.lockTargets("act-2")).thenReturn(List.of());
        when(mapper.lockOutbox(9L)).thenReturn(initial);

        assertThatThrownBy(() -> service.finishProjection(9L, "worker-old", 3L))
                .isInstanceOf(PlatformException.class)
                .extracting(failure -> ((PlatformException) failure).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(mapper, never()).finishOutbox(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    private ReleaseTransactionService service(ReleaseMapper mapper) {
        return new ReleaseTransactionService(
                mapper,
                new AuditService(mock(AuditMapper.class), new ObjectMapper().findAndRegisterModules()),
                new CanonicalJsonCodec(new ObjectMapper().findAndRegisterModules()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ReleaseRecord release() {
        return new ReleaseRecord(
                "rel-2", "r2", "TEST", "sample", "READY", "{}", new byte[0],
                "a".repeat(64), "1", new byte[0], "key", "SHA256withRSA", null, null,
                "admin", NOW, null, null);
    }

    private ReleaseActivationRecord activation() {
        return new ReleaseActivationRecord(
                "act-2", "TEST", "sample", "rel-1", "rel-2", 7L, 8L,
                "PUBLISH", "PENDING", "req", "admin", NOW.plusSeconds(5), NOW, null);
    }

    private ReleaseOutboxRecord outbox(String worker, long fence) {
        return new ReleaseOutboxRecord(
                9L, "act-2", "TEST:sample", 8L, "{}", new byte[0], "NEW", 1,
                NOW, worker, NOW.plusSeconds(10), fence, null, NOW, NOW, null);
    }
}
