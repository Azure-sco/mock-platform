package com.xuntian.mock.control.requestlog;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PageResult;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public final class RequestLogQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final RequestLogMapper requestLogMapper;

    public RequestLogQueryService(RequestLogMapper requestLogMapper) {
        this.requestLogMapper = requestLogMapper;
    }

    public PageResult<RequestLogRecord> find(RequestLogFilter filter, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "page must be >= 0 and size must be between 1 and 100");
        }
        if (filter.createdFrom() != null
                && filter.createdTo() != null
                && !filter.createdFrom().isBefore(filter.createdTo())) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "createdFrom must be before createdTo");
        }
        if ((filter.businessNoHmac() == null) != (filter.hmacKeyVersion() == null)) {
            throw new PlatformException(
                    ErrorCode.INVALID_REQUEST,
                    "businessNoHmac and hmacKeyVersion must be provided together");
        }
        if (filter.mockRequestId() != null && filter.appCode() == null) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "appCode is required with mockRequestId");
        }
        long total = requestLogMapper.count(filter);
        long offset = (long) page * size;
        return new PageResult<>(requestLogMapper.selectPage(filter, size, offset), total, page, size);
    }

    public RequestLogRecord detail(String id, LocalDate createdDate) {
        String normalizedId = required(id, "requestLogId", 64);
        if (createdDate == null) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "createdDate is required");
        }
        Instant start = createdDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        RequestLogRecord record = requestLogMapper.selectDetail(normalizedId, start, start.plusSeconds(86_400));
        if (record == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Request log not found");
        }
        return record;
    }

    public static String optional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return required(value, field, maxLength);
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return value.trim();
    }
}
