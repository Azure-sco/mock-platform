package com.xuntian.mock.control.approval;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.audit.AuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class ApprovalServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);

    @Test
    void lastModifierCannotApproveOwnChecksum() {
        ApprovalMapper mapper = mock(ApprovalMapper.class);
        ApprovalSubjectHandler handler = handler("editor");
        ApprovalService service = service(mapper, handler);
        when(mapper.lockById(7L)).thenReturn(request(7L, 1));

        assertThatThrownBy(() -> service.decide(
                7L, "APPROVE", null,
                new OperatorContext("editor", Set.of("MOCK_APPROVER"), "req-1")))
                .isInstanceOf(PlatformException.class)
                .extracting(failure -> ((PlatformException) failure).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(mapper, never()).insertDecision(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectionTerminatesRequestAndTransitionsExactChecksumSubject() {
        ApprovalMapper mapper = mock(ApprovalMapper.class);
        ApprovalSubjectHandler handler = handler("editor");
        ApprovalService service = service(mapper, handler);
        ApprovalRequestRecord pending = request(8L, 2);
        ApprovalRequestRecord rejected = new ApprovalRequestRecord(
                8L, "SCENARIO_VERSION", 42L, CHECKSUM, "DUAL", 2,
                "REJECTED", "author", NOW, NOW);
        when(mapper.lockById(8L)).thenReturn(pending);
        when(mapper.complete(8L, "REJECTED", NOW)).thenReturn(1);
        when(mapper.selectById(8L)).thenReturn(rejected);
        when(mapper.selectDecisions(8L)).thenReturn(List.of());
        when(handler.markRejected(42L, 8L, CHECKSUM)).thenReturn(1);

        service.decide(8L, "REJECT", "unsafe", new OperatorContext(
                "reviewer", Set.of("MOCK_APPROVER"), "req-2"));

        verify(handler).markRejected(42L, 8L, CHECKSUM);
        verify(mapper).complete(8L, "REJECTED", NOW);
    }

    private ApprovalService service(ApprovalMapper mapper, ApprovalSubjectHandler handler) {
        return new ApprovalService(
                mapper,
                new AuditService(mock(AuditMapper.class), new ObjectMapper().findAndRegisterModules()),
                Clock.fixed(NOW, ZoneOffset.UTC), List.of(handler));
    }

    private ApprovalSubjectHandler handler(String modifier) {
        ApprovalSubjectHandler handler = mock(ApprovalSubjectHandler.class);
        when(handler.objectType()).thenReturn("SCENARIO_VERSION");
        when(handler.currentSubject(42L)).thenReturn(
                new ApprovalSubjectHandler.ApprovalSubject(CHECKSUM, modifier));
        return handler;
    }

    private ApprovalRequestRecord request(long id, int count) {
        return new ApprovalRequestRecord(
                id, "SCENARIO_VERSION", 42L, CHECKSUM, "DUAL", count,
                "PENDING", "author", NOW, null);
    }
}
