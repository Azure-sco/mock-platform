package com.xuntian.mock.control.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.api.ApiMapper;
import com.xuntian.mock.control.api.ApiService;
import com.xuntian.mock.control.audit.AuditMapper;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.provider.ProviderMapper;
import com.xuntian.mock.control.provider.ProviderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ContractVersionMapper contractMapper;

    @Mock
    private ApiMapper apiMapper;

    @Mock
    private ProviderMapper providerMapper;

    @Mock
    private AuditMapper auditMapper;

    @Test
    void checksumIsStableAcrossJsonObjectFieldOrder() throws Exception {
        List<String> checksums = new ArrayList<>();
        when(apiMapper.lockById(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(contractMapper.nextVersionNo(anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            checksums.add(invocation.getArgument(10, String.class));
            return 1;
        }).when(contractMapper).insert(
                anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(contractMapper.selectByApiAndVersion(anyLong(), eq(1))).thenAnswer(invocation ->
                record(
                        invocation.getArgument(0, Long.class),
                        invocation.getArgument(0, Long.class),
                        "DRAFT",
                        checksums.get(checksums.size() - 1),
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\"}"));
        ContractService service = service();

        JsonNode requestA = objectMapper.readTree("{\"type\":\"object\",\"properties\":{\"z\":{\"type\":\"string\"},\"a\":{\"type\":\"number\"}}}");
        JsonNode requestB = objectMapper.readTree("{\"properties\":{\"a\":{\"type\":\"number\"},\"z\":{\"type\":\"string\"}},\"type\":\"object\"}");
        JsonNode response = objectMapper.readTree("{\"type\":\"object\"}");

        service.create(10L, command(requestA, response), operator());
        service.create(11L, command(requestB, response), operator());

        assertThat(checksums).hasSize(2);
        assertThat(checksums.get(0)).isEqualTo(checksums.get(1));
    }

    @Test
    void validatesThenPublishesWithoutChangingChecksum() {
        ContractVersionRecord draft = record(1L, 10L, "DRAFT", "checksum-1", schema(), schema());
        ContractVersionRecord validated = record(1L, 10L, "VALIDATED", "checksum-1", schema(), schema());
        ContractVersionRecord published = record(1L, 10L, "PUBLISHED", "checksum-1", schema(), schema());
        when(contractMapper.selectById(1L)).thenReturn(draft, validated, validated, published);
        when(contractMapper.markValidated(1L)).thenReturn(1);
        when(contractMapper.publish(1L, "admin-01")).thenReturn(1);
        ContractService service = service();

        ContractService.ContractView validatedView = service.validate(1L, operator());
        ContractService.ContractView publishedView = service.publish(1L, operator());

        assertThat(validatedView.status()).isEqualTo("VALIDATED");
        assertThat(publishedView.status()).isEqualTo("PUBLISHED");
        assertThat(publishedView.checksum()).isEqualTo("checksum-1");
        verify(contractMapper).markValidated(1L);
        verify(contractMapper).publish(1L, "admin-01");
    }

    @Test
    void rejectsMutationOfPublishedContract() {
        when(contractMapper.selectById(1L))
                .thenReturn(record(1L, 10L, "PUBLISHED", "checksum-1", schema(), schema()));
        ContractService service = service();

        assertThatThrownBy(() -> service.validate(1L, operator()))
                .isInstanceOf(PlatformException.class)
                .hasMessage("Published contract cannot be modified");
        verify(contractMapper, never()).markValidated(1L);
    }

    @Test
    void rejectsConcurrentStatusChangeDuringValidation() {
        when(contractMapper.selectById(1L))
                .thenReturn(record(1L, 10L, "DRAFT", "checksum-1", schema(), schema()));
        when(contractMapper.markValidated(1L)).thenReturn(0);
        ContractService service = service();

        assertThatThrownBy(() -> service.validate(1L, operator()))
                .isInstanceOf(PlatformException.class)
                .hasMessage("Contract status changed concurrently");
        verify(auditMapper, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void returnsDeterministicFieldDiffForSameApi() {
        ContractVersionRecord base = record(
                1L,
                10L,
                "PUBLISHED",
                "base",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}",
                schema());
        ContractVersionRecord current = record(
                2L,
                10L,
                "DRAFT",
                "current",
                "{\"type\":\"object\",\"properties\":{\"age\":{\"type\":\"integer\"},\"name\":{\"type\":\"number\"}}}",
                schema());
        when(contractMapper.selectById(2L)).thenReturn(current);
        when(contractMapper.selectById(1L)).thenReturn(base);
        ContractService service = service();

        ContractService.DiffResult result = service.diff(2L, 1L);

        assertThat(result.changes())
                .extracting(ContractService.DiffEntry::path)
                .containsExactly(
                        "/requestSchema/properties/age",
                        "/requestSchema/properties/name/type");
        assertThat(result.changes())
                .extracting(ContractService.DiffEntry::changeType)
                .containsExactly("ADDED", "CHANGED");
    }

    private ContractService service() {
        AuditService auditService = new AuditService(auditMapper, objectMapper);
        ProviderService providerService = new ProviderService(providerMapper, auditService);
        ApiService apiService = new ApiService(apiMapper, providerService, auditService);
        return new ContractService(contractMapper, apiService, auditService, objectMapper);
    }

    private ContractService.CreateCommand command(JsonNode request, JsonNode response) {
        return new ContractService.CreateCommand(request, response, null, null, null, null, "MANUAL", null);
    }

    private OperatorContext operator() {
        return new OperatorContext("admin-01", Set.of("MOCK_ADMIN"), "req-01");
    }

    private String schema() {
        return "{\"type\":\"object\"}";
    }

    private ContractVersionRecord record(
            long id,
            long apiId,
            String status,
            String checksum,
            String requestSchema,
            String responseSchema) {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new ContractVersionRecord(
                id,
                apiId,
                1,
                status,
                requestSchema,
                responseSchema,
                null,
                null,
                null,
                null,
                "MANUAL",
                null,
                checksum,
                "admin-01",
                now,
                "PUBLISHED".equals(status) ? "admin-01" : null,
                "PUBLISHED".equals(status) ? now : null);
    }
}
