package com.xuntian.mock.runtime.admission;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@Profile("!local & !test")
public final class AdmissionRefreshCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(AdmissionRefreshCoordinator.class);
    private final AdmissionLeaseSource source;
    private final AdmissionEnvelopeVerifier verifier;
    private final AdmissionSnapshotRegistry registry;
    private final AdmissionAckPort acks;
    private final RuntimeProperties properties;

    public AdmissionRefreshCoordinator(
            AdmissionLeaseSource source,
            AdmissionEnvelopeVerifier verifier,
            AdmissionSnapshotRegistry registry,
            AdmissionAckPort acks,
            RuntimeProperties properties) {
        this.source = source;
        this.verifier = verifier;
        this.registry = registry;
        this.acks = acks;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 5000L)
    public void poll() {
        Instant now = Instant.now();
        for (String app : properties.getPublishedApps()) {
            refresh(new AdmissionScope(properties.getEnvironment(), app), now);
        }
    }

    public RefreshResult refresh(AdmissionScope scope, Instant now) {
        try {
            Optional<byte[]> bytes = source.load(scope);
            if (bytes.isEmpty()) return RefreshResult.MISSING_LKG_RETAINED;
            VerifiedAdmissionSnapshot verified = verifier.verify(bytes.get(), scope, now);
            AdmissionSnapshotRegistry.ApplyResult result = registry.apply(verified);
            if (result == AdmissionSnapshotRegistry.ApplyResult.APPLIED) {
                acks.ready(verified, properties.getRuntimeNodeId(), now);
                return RefreshResult.READY;
            }
            return RefreshResult.STALE_IGNORED;
        } catch (AdmissionVerificationException failure) {
            LOG.error("Admission refresh rejected: environment={}, app={}, reason={}",
                    scope.environment(), scope.appCode(), failure.reason());
            return RefreshResult.REJECTED_LKG_RETAINED;
        } catch (RuntimeException failure) {
            LOG.error("Admission refresh failed: environment={}, app={}",
                    scope.environment(), scope.appCode(), failure);
            return RefreshResult.SOURCE_FAILED_LKG_RETAINED;
        }
    }

    public enum RefreshResult {
        READY,
        STALE_IGNORED,
        MISSING_LKG_RETAINED,
        REJECTED_LKG_RETAINED,
        SOURCE_FAILED_LKG_RETAINED
    }
}
