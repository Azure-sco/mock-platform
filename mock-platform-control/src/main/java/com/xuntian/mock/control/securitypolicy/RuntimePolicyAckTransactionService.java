package com.xuntian.mock.control.securitypolicy;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RuntimePolicyAckTransactionService {

    private final SecurityPolicyMapper mapper;

    public RuntimePolicyAckTransactionService(SecurityPolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public RuntimePolicyAckService.AckView record(
            RuntimePolicyAckService.Command command,
            Set<String> readyNodes,
            Instant now) {
        SecurityPolicyBindingRecord binding = mapper.lockBinding("APP_ACL", command.scopeKey());
        if (binding == null || !binding.id().equals(command.bindingId())
                || binding.bindingVersion() != command.bindingVersion()
                || binding.desiredPolicyVersionId() != command.policyVersionId()) {
            throw new PlatformException(ErrorCode.CONFLICT, "Runtime policy ACK does not match the locked Binding");
        }
        mapper.upsertRuntimeAck(
                command.runtimeNodeId(), binding.id(), command.environment(), command.appCode(),
                binding.desiredPolicyVersionId(), binding.bindingVersion(), command.status(),
                command.errorMasked(), command.reportedAt() == null ? now : command.reportedAt());
        List<RuntimePolicyAckRecord> acks = mapper.selectRuntimeAcks(binding.id(), binding.bindingVersion());
        Set<String> readyAckNodes = acks.stream()
                .filter(ack -> "READY".equals(ack.status()))
                .map(RuntimePolicyAckRecord::runtimeNodeId)
                .collect(Collectors.toSet());
        boolean effective = !readyNodes.isEmpty() && readyAckNodes.containsAll(readyNodes);
        if (effective) {
            mapper.markAdmissionEffective(binding.id(), binding.bindingVersion(), now, "runtime-policy-ack");
        }
        return new RuntimePolicyAckService.AckView(
                binding.id(), binding.bindingVersion(), command.status(), effective);
    }
}
