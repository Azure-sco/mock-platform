package com.xuntian.mock.control.securitypolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
public final class AdmissionLeaseProjector {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdmissionLeaseProjector.class);
    private final String workerId = "admission-lease-projector-" + UUID.randomUUID();
    private final SecurityPolicyMapper mapper;
    private final SecurityPolicyService policyService;
    private final AdmissionEnvelopeFactory envelopeFactory;
    private final AdmissionLeasePublisher publisher;
    private final AdmissionLeaseTransactionService transactions;
    private final Clock clock;

    public AdmissionLeaseProjector(
            SecurityPolicyMapper mapper,
            SecurityPolicyService policyService,
            AdmissionEnvelopeFactory envelopeFactory,
            AdmissionLeasePublisher publisher,
            AdmissionLeaseTransactionService transactions,
            Clock clock) {
        this.mapper = mapper;
        this.policyService = policyService;
        this.envelopeFactory = envelopeFactory;
        this.publisher = publisher;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mock.admission.lease.fixed-delay-ms:20000}")
    public void renewCurrentBindings() {
        for (SecurityPolicyBindingRecord candidate : mapper.selectLiveAdmissionBindings()) {
            try {
                renew(candidate);
            } catch (RuntimeException failure) {
                LOGGER.error(
                        "Admission lease projection failed bindingId={} bindingVersion={} errorType={}",
                        candidate.id(), candidate.bindingVersion(), failure.getClass().getSimpleName());
            }
        }
    }

    private void renew(SecurityPolicyBindingRecord candidate) {
        SecurityPolicyBindingRecord current = mapper.selectBindingById(candidate.id());
        if (current == null || current.bindingVersion() != candidate.bindingVersion()
                || current.desiredPolicyVersionId() != candidate.desiredPolicyVersionId()
                || !"LIVE_ADMISSION".equals(current.effectMode())
                || !("PUBLISHING".equals(current.status()) || "BOUND".equals(current.status()))) {
            return;
        }
        SecurityPolicyVersionRecord policy = mapper.selectVersionById(current.desiredPolicyVersionId());
        if (policy == null || !"APP_ACL".equals(policy.policyType()) || !"PUBLISHED".equals(policy.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Admission Binding desired policy is not published");
        }
        JsonNode config = policyService.config(policy);
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        AdmissionEnvelopeFactory.PreparedEnvelope envelope = envelopeFactory.create(
                current.id(), policy, current.bindingVersion(), config, issuedAt);
        SecurityPolicyBindingRecord confirmed = mapper.selectBindingById(current.id());
        if (confirmed == null || confirmed.bindingVersion() != current.bindingVersion()
                || confirmed.desiredPolicyVersionId() != policy.id()) {
            return;
        }
        if (!publisher.publishIfNewer(
                envelope.environment(), envelope.appCode(), current.bindingVersion(),
                envelope.issuedAt(), envelope.notAfter(), envelope.canonicalBytes())) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Admission lease was not renewed");
        }
        if ("PUBLISHING".equals(confirmed.status())) {
            transactions.markProjected(
                    current.id(), current.bindingVersion(), policy.id(), issuedAt, workerId);
        }
    }
}
