package com.xuntian.mock.control.securitypolicy;

import com.xuntian.mock.common.RequestIds;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public final class AdmissionAckMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdmissionAckMonitor.class);
    private final SecurityPolicyMapper mapper;
    private final RuntimeNodeDiscoveryPort discovery;
    private final AuditService auditService;
    private final AdmissionLeaseTransactionService transactions;
    private final Clock clock;

    public AdmissionAckMonitor(
            SecurityPolicyMapper mapper,
            RuntimeNodeDiscoveryPort discovery,
            AuditService auditService,
            AdmissionLeaseTransactionService transactions,
            Clock clock) {
        this.mapper = mapper;
        this.discovery = discovery;
        this.auditService = auditService;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mock.admission.ack-monitor.fixed-delay-ms:5000}")
    public void removeStaleNodes() {
        if (!discovery.available()) return;
        for (SecurityPolicyBindingRecord binding : mapper.selectLiveAdmissionBindings()) {
            if (!"BOUND".equals(binding.status()) || binding.boundAt() == null
                    || binding.effectiveAt() != null
                    || binding.boundAt().isAfter(clock.instant().minusSeconds(60))) {
                continue;
            }
            Set<String> acknowledged = mapper.selectRuntimeAcks(binding.id(), binding.bindingVersion()).stream()
                    .filter(ack -> "READY".equals(ack.status()))
                    .map(RuntimePolicyAckRecord::runtimeNodeId)
                    .collect(Collectors.toSet());
            String appCode = appCode(binding);
            String environment = environment(binding);
            for (String node : discovery.readyNodes(appCode, environment)) {
                if (acknowledged.contains(node)) continue;
                if (discovery.removeFromTraffic(node)) {
                    OperatorContext system = new OperatorContext(
                            "admission-ack-monitor", Set.of("SYSTEM"), RequestIds.generate());
                    auditService.record(
                            system, "ADMISSION_STALE_NODE_REMOVE", "RUNTIME_NODE", node, null, null,
                            Map.of("bindingId", binding.id(), "bindingVersion", binding.bindingVersion()));
                    LOGGER.error(
                            "Runtime node removed after missing Admission ACK nodeId={} bindingId={} bindingVersion={}",
                            node, binding.id(), binding.bindingVersion());
                }
            }
            Set<String> remaining = discovery.readyNodes(appCode, environment);
            if (!remaining.isEmpty() && acknowledged.containsAll(remaining)) {
                transactions.markEffective(
                        binding.id(), binding.bindingVersion(), clock.instant(), "admission-ack-monitor");
            }
        }
    }

    private String environment(SecurityPolicyBindingRecord binding) {
        int separator = binding.scopeKey().indexOf(':');
        return separator < 0 ? binding.scopeKey() : binding.scopeKey().substring(0, separator);
    }

    private String appCode(SecurityPolicyBindingRecord binding) {
        int separator = binding.scopeKey().indexOf(':');
        return separator < 0 ? binding.scopeKey() : binding.scopeKey().substring(separator + 1);
    }
}
