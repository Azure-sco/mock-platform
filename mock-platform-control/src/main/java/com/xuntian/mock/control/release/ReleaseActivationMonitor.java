package com.xuntian.mock.control.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public final class ReleaseActivationMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseActivationMonitor.class);
    private static final Duration LEFT_CONFIRMATION = Duration.ofSeconds(10);
    private final ReleaseMapper mapper;
    private final ReleaseTransactionService transactions;
    private final RuntimeNodeDiscoveryPort discovery;
    private final RuntimeTrafficGovernancePort traffic;
    private final Clock clock;

    public ReleaseActivationMonitor(
            ReleaseMapper mapper,
            ReleaseTransactionService transactions,
            RuntimeNodeDiscoveryPort discovery,
            RuntimeTrafficGovernancePort traffic,
            Clock clock) {
        this.mapper = mapper;
        this.transactions = transactions;
        this.discovery = discovery;
        this.traffic = traffic;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mock.release.activation-monitor.fixed-delay-ms:1000}")
    public void reconcile() {
        Instant now = clock.instant();
        for (ReleaseActivationRecord activation : mapper.selectOpenActivations()) {
            try {
                reconcileLeft(activation, now);
                reconcileTimeout(activation, now);
            } catch (RuntimeException failure) {
                LOGGER.error(
                        "Release Activation reconciliation failed activationId={} state={}",
                        activation.id(), activation.status());
            }
        }
    }

    private void reconcileLeft(ReleaseActivationRecord activation, Instant now) {
        for (ActivationTargetRecord target : mapper.selectTargets(activation.id())) {
            if (target.required()
                    && Set.of("WAITING", "FAILED").contains(target.status())
                    && !now.isBefore(target.capturedAt().plus(LEFT_CONFIRMATION))
                    && discovery.continuouslyDeregistered(
                            activation.environment(), activation.appCode(), target.runtimeNodeId(), LEFT_CONFIRMATION)) {
                transactions.markLeft(activation.id(), target.runtimeNodeId());
            }
        }
    }

    private void reconcileTimeout(ReleaseActivationRecord activation, Instant now) {
        if (now.isBefore(activation.deadlineAt()) || "APPLIED".equals(activation.status())) return;
        List<ActivationTargetRecord> targets = mapper.selectTargets(activation.id());
        Set<String> removed = new HashSet<>();
        for (ActivationTargetRecord target : targets) {
            if (target.required() && !"READY".equals(target.status())) {
                traffic.removeFromTraffic(activation.environment(), activation.appCode(), target.runtimeNodeId());
                if (traffic.isRemovedFromTraffic(
                        activation.environment(), activation.appCode(), target.runtimeNodeId())) {
                    removed.add(target.runtimeNodeId());
                }
            }
        }
        transactions.markPartial(activation.id(), Set.copyOf(removed));
    }
}
