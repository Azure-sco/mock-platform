package com.xuntian.mock.control.outbox;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.sdkconfig.ActiveSdkConfigRecord;
import com.xuntian.mock.control.sdkconfig.SdkConfigActivationRecord;
import com.xuntian.mock.control.sdkconfig.SdkConfigMapper;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyBindingRecord;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class ConfigOutboxTransactionService {

    static final int MAX_ATTEMPTS = 8;
    private final ConfigPublishOutboxMapper outboxMapper;
    private final SecurityPolicyMapper securityPolicyMapper;
    private final SdkConfigMapper sdkConfigMapper;
    private final Clock clock;

    public ConfigOutboxTransactionService(
            ConfigPublishOutboxMapper outboxMapper,
            SecurityPolicyMapper securityPolicyMapper,
            SdkConfigMapper sdkConfigMapper,
            Clock clock) {
        this.outboxMapper = outboxMapper;
        this.securityPolicyMapper = securityPolicyMapper;
        this.sdkConfigMapper = sdkConfigMapper;
        this.clock = clock;
    }

    @Transactional
    public ConfigPublishOutboxRecord claim(String worker, Duration lease) {
        if (worker == null || worker.isBlank() || worker.length() > 128
                || lease == null || lease.isNegative() || lease.isZero()
                || lease.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Outbox lease request is invalid");
        }
        Instant now = clock.instant();
        ConfigPublishOutboxRecord candidate = outboxMapper.lockNextClaimable(now, MAX_ATTEMPTS);
        if (candidate == null) return null;
        if (outboxMapper.claim(
                candidate.id(), worker, now.plus(lease), candidate.fencingToken(), now) != 1) {
            return null;
        }
        return outboxMapper.selectById(candidate.id());
    }

    @Transactional
    public void finish(long outboxId, String worker, long fencingToken, boolean projectedExternally) {
        ConfigPublishOutboxRecord hint = outboxMapper.selectById(outboxId);
        if (hint == null) return;
        switch (hint.aggregateType()) {
            case "ADMISSION_BINDING" -> finishAdmission(hint, worker, fencingToken, projectedExternally);
            case "SDK_CONFIG_ACTIVATION" -> finishSdkConfig(hint, worker, fencingToken, projectedExternally);
            default -> throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Unsupported config outbox aggregate type");
        }
    }

    @Transactional
    public void fail(long outboxId, String worker, long fencingToken, Throwable failure) {
        ConfigPublishOutboxRecord outbox = outboxMapper.lockById(outboxId);
        if (!owns(outbox, worker, fencingToken)) return;
        boolean terminal = outbox.attemptCount() >= MAX_ATTEMPTS;
        long delaySeconds = Math.min(60L, 1L << Math.min(6, Math.max(0, outbox.attemptCount() - 1)));
        Instant next = terminal ? null : clock.instant().plusSeconds(delaySeconds);
        outboxMapper.markFailed(
                outboxId, worker, fencingToken, next,
                failure == null ? "ProjectionFailure" : mask(failure.getClass().getSimpleName()));
    }

    @Transactional
    public void requeueActiveSdkConfig(String appCode, String environment) {
        ActiveSdkConfigRecord active = sdkConfigMapper.lockActive(appCode, environment);
        if (active == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Active SDK config not found");
        ConfigPublishOutboxRecord outbox = outboxMapper.selectByAggregate(
                "SDK_CONFIG_ACTIVATION", active.activationId());
        if (outbox == null) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Active SDK config outbox is missing");
        }
        if (!"PUBLISHED".equals(outbox.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Active SDK config outbox is not recoverable yet");
        }
        if (outboxMapper.requeuePublishedSdkConfig(active.activationId(), clock.instant()) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Active SDK config outbox changed concurrently");
        }
    }

    private void finishAdmission(
            ConfigPublishOutboxRecord hint,
            String worker,
            long fencingToken,
            boolean projectedExternally) {
        AggregateVersion aggregate = admissionAggregate(hint.aggregateId());
        SecurityPolicyBindingRecord binding = securityPolicyMapper.lockBindingById(aggregate.id());
        ConfigPublishOutboxRecord outbox = outboxMapper.lockById(hint.id());
        requireOwner(outbox, worker, fencingToken);
        boolean current = binding != null && binding.bindingVersion() == aggregate.version()
                && "PUBLISHING".equals(binding.status());
        if (current && !projectedExternally) {
            throw new PlatformException(ErrorCode.CONFLICT, "Current Admission Binding was not projected");
        }
        markPublished(outbox, worker, fencingToken);
        if (current && securityPolicyMapper.markAdmissionProjected(
                binding.id(), binding.bindingVersion(), binding.desiredPolicyVersionId(),
                clock.instant(), worker) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Admission Binding changed during projection finalize");
        }
    }

    private void finishSdkConfig(
            ConfigPublishOutboxRecord hint,
            String worker,
            long fencingToken,
            boolean projectedExternally) {
        SdkConfigActivationRecord activationHint = sdkConfigMapper.selectActivation(hint.aggregateId());
        if (activationHint == null) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "SDK config activation is missing");
        }
        ActiveSdkConfigRecord active = sdkConfigMapper.lockActive(
                activationHint.appCode(), activationHint.environment());
        SdkConfigActivationRecord activation = sdkConfigMapper.lockActivation(activationHint.id());
        ConfigPublishOutboxRecord outbox = outboxMapper.lockById(hint.id());
        requireOwner(outbox, worker, fencingToken);
        boolean current = active != null && activation != null
                && active.activationId().equals(activation.id())
                && active.desiredConfigVersion() == activation.toConfigVersion()
                && "PENDING".equals(activation.status());
        if (current && !projectedExternally) {
            throw new PlatformException(ErrorCode.CONFLICT, "Current SDK config was not projected");
        }
        markPublished(outbox, worker, fencingToken);
        if (current) {
            if (sdkConfigMapper.markActivationProjected(activation.id()) != 1
                    || sdkConfigMapper.markPublished(
                    activation.sdkConfigEnvelopeId(), activation.operator(), clock.instant()) != 1) {
                throw new PlatformException(ErrorCode.CONFLICT, "SDK config changed during projection finalize");
            }
        }
    }

    private void markPublished(ConfigPublishOutboxRecord outbox, String worker, long fencingToken) {
        if (outboxMapper.markPublished(outbox.id(), worker, fencingToken, clock.instant()) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Config outbox lease was fenced by another worker");
        }
    }

    private void requireOwner(ConfigPublishOutboxRecord outbox, String worker, long fencingToken) {
        if (!owns(outbox, worker, fencingToken)) {
            throw new PlatformException(ErrorCode.CONFLICT, "Config outbox lease was fenced by another worker");
        }
    }

    private boolean owns(ConfigPublishOutboxRecord outbox, String worker, long fencingToken) {
        return outbox != null && worker.equals(outbox.leaseOwner())
                && fencingToken == outbox.fencingToken()
                && ("NEW".equals(outbox.status()) || "FAILED".equals(outbox.status()));
    }

    static AggregateVersion admissionAggregate(String aggregateId) {
        int separator = aggregateId == null ? -1 : aggregateId.lastIndexOf(':');
        if (separator <= 0 || separator == aggregateId.length() - 1) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Admission outbox aggregate ID is invalid");
        }
        try {
            return new AggregateVersion(
                    aggregateId.substring(0, separator), Long.parseLong(aggregateId.substring(separator + 1)));
        } catch (NumberFormatException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Admission outbox Binding version is invalid", failure);
        }
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return "ProjectionFailure";
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    record AggregateVersion(String id, long version) {
    }
}
