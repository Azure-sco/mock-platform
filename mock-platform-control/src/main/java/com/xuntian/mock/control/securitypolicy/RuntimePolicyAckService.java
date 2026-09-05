package com.xuntian.mock.control.securitypolicy;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@Service
public class RuntimePolicyAckService {

    private final SecurityPolicyMapper mapper;
    private final RuntimeNodeDiscoveryPort discovery;
    private final RuntimePolicyAckTransactionService transactions;
    private final Clock clock;

    public RuntimePolicyAckService(
            SecurityPolicyMapper mapper,
            RuntimeNodeDiscoveryPort discovery,
            RuntimePolicyAckTransactionService transactions,
            Clock clock) {
        this.mapper = mapper;
        this.discovery = discovery;
        this.transactions = transactions;
        this.clock = clock;
    }

    public AckView record(Command command) {
        String status = required(command.status(), "status", 16).toUpperCase(Locale.ROOT);
        if (!Set.of("READY", "FAILED", "STALE").contains(status)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Runtime policy ACK status is invalid");
        }
        Command normalized = new Command(
                required(command.runtimeNodeId(), "runtimeNodeId", 128),
                required(command.bindingId(), "bindingId", 64),
                required(command.scopeKey(), "scopeKey", 512),
                required(command.environment(), "environment", 32).toUpperCase(Locale.ROOT),
                required(command.appCode(), "appCode", 128),
                command.policyVersionId(), command.bindingVersion(), status,
                optional(command.errorMasked(), 512), command.reportedAt());
        SecurityPolicyBindingRecord binding = mapper.selectBinding("APP_ACL", normalized.scopeKey());
        if (binding == null || !binding.id().equals(normalized.bindingId())
                || binding.bindingVersion() != normalized.bindingVersion()
                || binding.desiredPolicyVersionId() != normalized.policyVersionId()) {
            throw new PlatformException(ErrorCode.CONFLICT, "Runtime policy ACK does not match the desired Binding");
        }
        Set<String> readyNodes = discovery.available()
                ? discovery.readyNodes(normalized.appCode(), normalized.environment()) : Set.of();
        return transactions.record(normalized, readyNodes, clock.instant());
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return value.trim();
    }

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > maxLength) throw new PlatformException(ErrorCode.INVALID_REQUEST, "errorMasked is invalid");
        return value.trim();
    }

    public record Command(
            String runtimeNodeId, String bindingId, String scopeKey, String environment, String appCode,
            long policyVersionId, long bindingVersion, String status, String errorMasked, Instant reportedAt) {
    }

    public record AckView(String bindingId, long bindingVersion, String status, boolean effective) {
    }
}
