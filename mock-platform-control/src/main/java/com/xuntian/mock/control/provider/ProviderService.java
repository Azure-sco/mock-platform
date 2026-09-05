package com.xuntian.mock.control.provider;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ProviderService {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");

    private final ProviderMapper providerMapper;
    private final AuditService auditService;

    public ProviderService(ProviderMapper providerMapper, AuditService auditService) {
        this.providerMapper = providerMapper;
        this.auditService = auditService;
    }

    public List<ProviderRecord> findAll() {
        return providerMapper.selectAll();
    }

    @Transactional
    public ProviderRecord create(CreateCommand command, OperatorContext operator) {
        String code = code(command.providerCode());
        String name = required(command.providerName(), "providerName", 128);
        String owner = required(command.owner(), "owner", 128);
        String status = status(command.status());
        try {
            providerMapper.insert(code, name, owner, status, operator.operatorId());
        } catch (DuplicateKeyException failure) {
            throw new PlatformException(ErrorCode.CONFLICT, "Provider code already exists", failure);
        }
        ProviderRecord created = providerMapper.selectByCode(code);
        auditService.record(
                operator,
                "PROVIDER_CREATE",
                "PROVIDER",
                created.id(),
                null,
                null,
                auditView(created));
        return created;
    }

    @Transactional
    public ProviderRecord update(long id, UpdateCommand command, OperatorContext operator) {
        ProviderRecord before = require(id);
        String name = required(command.providerName(), "providerName", 128);
        String owner = required(command.owner(), "owner", 128);
        String status = status(command.status());
        providerMapper.update(id, name, owner, status, operator.operatorId());
        ProviderRecord updated = require(id);
        auditService.record(
                operator,
                "PROVIDER_UPDATE",
                "PROVIDER",
                id,
                null,
                auditView(before),
                auditView(updated));
        return updated;
    }

    public ProviderRecord require(long id) {
        ProviderRecord provider = providerMapper.selectById(id);
        if (provider == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Provider not found");
        }
        return provider;
    }

    private String code(String value) {
        if (value == null || !CODE.matcher(value.trim()).matches()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "providerCode is invalid");
        }
        return value.trim();
    }

    private String status(String value) {
        String normalized = value == null || value.isBlank() ? "ENABLED" : value.trim().toUpperCase();
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

    private Map<String, Object> auditView(ProviderRecord provider) {
        return Map.of(
                "id", provider.id(),
                "providerCode", provider.providerCode(),
                "providerName", provider.providerName(),
                "owner", provider.owner(),
                "status", provider.status());
    }

    public record CreateCommand(String providerCode, String providerName, String owner, String status) {
    }

    public record UpdateCommand(String providerName, String owner, String status) {
    }
}
