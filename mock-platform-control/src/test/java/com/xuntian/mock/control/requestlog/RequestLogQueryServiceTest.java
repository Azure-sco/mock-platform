package com.xuntian.mock.control.requestlog;

import com.xuntian.mock.common.PageResult;
import com.xuntian.mock.common.PlatformException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestLogQueryServiceTest {

    @Mock
    private RequestLogMapper requestLogMapper;

    @Test
    void queriesBoundedPageAndPreservesTotal() {
        RequestLogFilter filter = new RequestLogFilter(
                "trace-1", "OA", "CREATE", null, null, null, null, null, null, null);
        RequestLogRecord row = row();
        when(requestLogMapper.count(filter)).thenReturn(21L);
        when(requestLogMapper.selectPage(filter, 20, 20L)).thenReturn(List.of(row));
        RequestLogQueryService service = new RequestLogQueryService(requestLogMapper);

        PageResult<RequestLogRecord> result = service.find(filter, 1, 20);

        assertThat(result.getTotal()).isEqualTo(21L);
        assertThat(result.getItems()).containsExactly(row);
    }

    @Test
    void requiresVersionAlongsideBusinessNumberHmac() {
        RequestLogFilter filter = new RequestLogFilter(
                null, null, null, null, null, null, "a".repeat(64), null, null, null);
        RequestLogQueryService service = new RequestLogQueryService(requestLogMapper);

        assertThatThrownBy(() -> service.find(filter, 0, 20))
                .isInstanceOf(PlatformException.class)
                .hasMessage("businessNoHmac and hmacKeyVersion must be provided together");
    }

    @Test
    void detailUsesUtcCreatedDateBoundaryForCompositePrimaryKey() {
        when(requestLogMapper.selectDetail(
                org.mockito.ArgumentMatchers.eq("log-1"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(row());
        RequestLogQueryService service = new RequestLogQueryService(requestLogMapper);

        service.detail("log-1", LocalDate.of(2026, 8, 31));

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(requestLogMapper).selectDetail(org.mockito.ArgumentMatchers.eq("log-1"), start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-08-31T00:00:00Z"));
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    private RequestLogRecord row() {
        Instant created = Instant.parse("2026-08-31T00:00:00Z");
        return new RequestLogRecord(
                "log-1",
                "mr-1",
                "trace-1",
                "TEST",
                "sample-app",
                null,
                null,
                "OA",
                "CREATE",
                null,
                null,
                null,
                null,
                null,
                null,
                "POST",
                "/oa/create",
                null,
                null,
                200,
                "MATCHED",
                10L,
                null,
                created.plusSeconds(86_400),
                created);
    }
}
