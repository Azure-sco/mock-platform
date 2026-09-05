package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

@Service
public final class RuntimeActivationAckService {

    private final ReleaseTransactionService transactions;

    public RuntimeActivationAckService(ReleaseTransactionService transactions) {
        this.transactions = transactions;
    }

    public ReleaseTransactionService.AckResult acknowledge(AckCommand command) {
        String environment = required(command.environment(), "environment", 32).toUpperCase(Locale.ROOT);
        String app = required(command.app(), "app", 128);
        String nodeId = required(command.runtimeNodeId(), "runtimeNodeId", 128);
        String releaseId = required(command.releaseId(), "releaseId", 64);
        if (command.activationVersion() <= 0) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "activationVersion must be positive");
        }
        String status = required(command.status(), "status", 16).toUpperCase(Locale.ROOT);
        if (!Set.of("READY", "FAILED").contains(status)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "ACK status must be READY or FAILED");
        }
        String error = optional(command.error(), 512);
        if ("FAILED".equals(status) && error == null) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "FAILED ACK requires a masked error");
        }
        return transactions.acknowledge(new ReleaseTransactionService.AckCommand(
                environment, app, nodeId, releaseId, command.activationVersion(), status, error));
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return value.trim();
    }

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalized.length() > maxLength) return normalized.substring(0, maxLength);
        return normalized;
    }

    public record AckCommand(
            String environment,
            String app,
            String runtimeNodeId,
            String releaseId,
            long activationVersion,
            String status,
            String error) {
    }
}
