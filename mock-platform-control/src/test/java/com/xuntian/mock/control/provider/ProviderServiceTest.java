package com.xuntian.mock.control.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditMapper;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderServiceTest {

    @Mock
    private ProviderMapper providerMapper;

    @Mock
    private AuditMapper auditMapper;

    @Test
    void createsProviderAndWritesAuditAfterPersistence() {
        ProviderRecord stored = provider(7L, "OA", "Office Automation", "ENABLED");
        when(providerMapper.selectByCode("OA")).thenReturn(stored);
        ProviderService service = service();

        ProviderRecord result = service.create(
                new ProviderService.CreateCommand("OA", "Office Automation", "team-a", null),
                operator());

        assertThat(result).isEqualTo(stored);
        verify(providerMapper).insert("OA", "Office Automation", "team-a", "ENABLED", "admin-01");
        verify(auditMapper).insert(
                org.mockito.ArgumentMatchers.eq("req-01"),
                org.mockito.ArgumentMatchers.eq("admin-01"),
                org.mockito.ArgumentMatchers.eq("PROVIDER_CREATE"),
                org.mockito.ArgumentMatchers.eq("PROVIDER"),
                org.mockito.ArgumentMatchers.eq("7"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                any());
    }

    @Test
    void rejectsInvalidCodeBeforeWriting() {
        ProviderService service = service();

        assertThatThrownBy(() -> service.create(
                new ProviderService.CreateCommand("bad code", "Name", "owner", "ENABLED"),
                operator()))
                .isInstanceOf(PlatformException.class)
                .hasMessage("providerCode is invalid");
        verify(providerMapper, never()).insert(any(), any(), any(), any(), any());
    }

    private OperatorContext operator() {
        return new OperatorContext("admin-01", Set.of("MOCK_ADMIN"), "req-01");
    }

    private ProviderService service() {
        return new ProviderService(providerMapper, new AuditService(auditMapper, new ObjectMapper()));
    }

    private ProviderRecord provider(long id, String code, String name, String status) {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new ProviderRecord(id, code, name, "team-a", status, "admin-01", now, "admin-01", now);
    }
}
