package com.xuntian.mock.control.audit;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PageResult;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Service;

@Service
public final class AuditQueryService {

    private final AuditMapper mapper;

    public AuditQueryService(AuditMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult<AuditRecord> find(AuditFilter filter, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new PlatformException(
                    ErrorCode.INVALID_REQUEST,
                    "page must be >= 0 and size must be between 1 and 100");
        }
        if (filter.createdFrom() != null && filter.createdTo() != null
                && !filter.createdFrom().isBefore(filter.createdTo())) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "createdFrom must be before createdTo");
        }
        long total = mapper.count(filter);
        return new PageResult<>(mapper.selectPage(filter, size, (long) page * size), total, page, size);
    }

    public static String optional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return normalized;
    }
}
