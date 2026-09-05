package com.xuntian.mock.control.api;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.provider.ProviderService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ApiService {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");

    private final ApiMapper apiMapper;
    private final ProviderService providerService;
    private final AuditService auditService;

    public ApiService(ApiMapper apiMapper, ProviderService providerService, AuditService auditService) {
        this.apiMapper = apiMapper;
        this.providerService = providerService;
        this.auditService = auditService;
    }

    public List<ApiRecord> findByProvider(long providerId) {
        providerService.require(providerId);
        return apiMapper.selectByProvider(providerId);
    }

    @Transactional
    public ApiRecord create(CreateCommand command, OperatorContext operator) {
        providerService.require(command.providerId());
        String code = code(command.apiCode());
        String name = required(command.apiName(), "apiName", 128);
        String method = method(command.httpMethod());
        String path = path(command.path());
        String contentType = required(command.contentType(), "contentType", 128);
        String owner = required(command.owner(), "owner", 128);
        String status = status(command.status());
        try {
            apiMapper.insert(
                    command.providerId(), code, name, method, path, contentType,
                    owner, status, operator.operatorId());
        } catch (DuplicateKeyException failure) {
            throw new PlatformException(ErrorCode.CONFLICT, "API code already exists for provider", failure);
        }
        ApiRecord created = apiMapper.selectByProviderAndCode(command.providerId(), code);
        auditService.record(operator, "API_CREATE", "API", created.id(), null, null, auditView(created));
        return created;
    }

    @Transactional
    public ApiRecord update(long id, UpdateCommand command, OperatorContext operator) {
        ApiRecord before = require(id);
        apiMapper.update(
                id,
                required(command.apiName(), "apiName", 128),
                method(command.httpMethod()),
                path(command.path()),
                required(command.contentType(), "contentType", 128),
                required(command.owner(), "owner", 128),
                status(command.status()),
                operator.operatorId());
        ApiRecord updated = require(id);
        auditService.record(operator, "API_UPDATE", "API", id, null, auditView(before), auditView(updated));
        return updated;
    }

    public ApiRecord require(long id) {
        ApiRecord api = apiMapper.selectById(id);
        if (api == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "API not found");
        }
        return api;
    }

    public void lockExisting(long id) {
        if (apiMapper.lockById(id) == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "API not found");
        }
    }

    private String code(String value) {
        if (value == null || !CODE.matcher(value.trim()).matches()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "apiCode is invalid");
        }
        return value.trim();
    }

    private String method(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(normalized)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "httpMethod is invalid");
        }
        return normalized;
    }

    private String path(String value) {
        String normalized = required(value, "path", 512);
        if (!normalized.startsWith("/") || normalized.contains("\\") || normalized.contains("..")) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "path is invalid");
        }
        return normalized;
    }

    private String status(String value) {
        String normalized = value == null || value.isBlank()
                ? "ENABLED"
                : value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "status must be ENABLED or DISABLED");
        }
        return normalized;
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return value.trim();
    }

    private Map<String, Object> auditView(ApiRecord api) {
        return Map.of(
                "id", api.id(),
                "providerId", api.providerId(),
                "apiCode", api.apiCode(),
                "apiName", api.apiName(),
                "httpMethod", api.httpMethod(),
                "path", api.path(),
                "contentType", api.contentType(),
                "owner", api.owner(),
                "status", api.status());
    }

    public record CreateCommand(
            long providerId,
            String apiCode,
            String apiName,
            String httpMethod,
            String path,
            String contentType,
            String owner,
            String status) {
    }

    public record UpdateCommand(
            String apiName,
            String httpMethod,
            String path,
            String contentType,
            String owner,
            String status) {
    }
}
