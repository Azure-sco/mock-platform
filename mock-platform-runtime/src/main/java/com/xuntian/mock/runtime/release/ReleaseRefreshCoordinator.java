package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!local & !test")
public final class ReleaseRefreshCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseRefreshCoordinator.class);
    private final ReleaseProjectionPort projection;
    private final ReleaseRecoveryPort recovery;
    private final ActivationAckPort acks;
    private final RuntimeSnapshotEnvelopeVerifier verifier;
    private final ReleaseSnapshotCache cache;
    private final LocalActiveReleaseRegistry registry;
    private final RuntimeProperties properties;
    private final ConcurrentHashMap<ReleaseScope, Object> scopeLocks = new ConcurrentHashMap<>();

    public ReleaseRefreshCoordinator(
            ReleaseProjectionPort projection,
            ReleaseRecoveryPort recovery,
            ActivationAckPort acks,
            RuntimeSnapshotEnvelopeVerifier verifier,
            ReleaseSnapshotCache cache,
            LocalActiveReleaseRegistry registry,
            RuntimeProperties properties) {
        this.projection = projection;
        this.recovery = recovery;
        this.acks = acks;
        this.verifier = verifier;
        this.cache = cache;
        this.registry = registry;
        this.properties = properties;
        if (!properties.getPublishedApps().isEmpty()
                && "runtime-node-unconfigured".equals(properties.getRuntimeNodeId())) {
            throw new IllegalStateException("runtimeNodeId is required when publishedApps are configured");
        }
    }

    @Scheduled(fixedDelay = 1000L)
    public void poll() {
        refreshAll(Instant.now());
    }

    public void refreshAll(Instant now) {
        for (String app : properties.getPublishedApps()) {
            try {
                refresh(new ReleaseScope(properties.getEnvironment(), app), now);
            } catch (RuntimeException failure) {
                LOG.error("Runtime Release refresh crashed for app={}", app, failure);
            }
        }
    }

    public RefreshResult refresh(ReleaseScope scope, Instant now) {
        Object lock = scopeLocks.computeIfAbsent(scope, ignored -> new Object());
        synchronized (lock) {
            return refreshLocked(scope, now);
        }
    }

    private RefreshResult refreshLocked(ReleaseScope scope, Instant now) {
        ActiveReleasePointer attemptedPointer = null;
        SnapshotVerificationException lastFailure = null;
        try {
            Optional<ActiveReleasePointer> pointer = projection.loadPointer(scope);
            if (pointer.isPresent()) {
                attemptedPointer = pointer.get();
                Optional<byte[]> envelope = projection.loadEnvelope(attemptedPointer.releaseId());
                if (envelope.isPresent()) {
                    return apply(scope, new ReleaseCandidate(attemptedPointer, envelope.get()), now, false);
                }
                lastFailure = failure(SnapshotVerificationException.Reason.SOURCE_UNAVAILABLE,
                        "Redis Release Snapshot is unavailable");
            }
        } catch (SnapshotVerificationException failure) {
            lastFailure = failure;
        } catch (RuntimeException failure) {
            lastFailure = failure(SnapshotVerificationException.Reason.SOURCE_UNAVAILABLE,
                    "Redis Release projection is unavailable", failure);
        }

        try {
            Optional<ReleaseCandidate> recovered = recovery.recover(scope);
            if (recovered.isPresent()) {
                attemptedPointer = recovered.get().pointer();
                RefreshResult result = apply(scope, recovered.get(), now, true);
                try {
                    projection.cacheRecovered(scope, recovered.get());
                } catch (RuntimeException cacheFailure) {
                    LOG.warn("Recovered Runtime Release could not be cached: environment={}, app={}",
                            scope.environment(), scope.app());
                }
                return result;
            }
        } catch (SnapshotVerificationException failure) {
            lastFailure = failure;
        } catch (RuntimeException failure) {
            lastFailure = failure(SnapshotVerificationException.Reason.SOURCE_UNAVAILABLE,
                    "MySQL Release recovery is unavailable", failure);
        }

        if (lastFailure == null) {
            lastFailure = failure(SnapshotVerificationException.Reason.SOURCE_UNAVAILABLE,
                    "No published Runtime Release is available");
        }
        if (attemptedPointer != null) {
            recordFailed(scope, attemptedPointer, lastFailure.reason(), now);
        }
        return new RefreshResult(Status.FAILED_LKG_RETAINED, attemptedPointer, lastFailure.reason());
    }

    private RefreshResult apply(
            ReleaseScope scope,
            ReleaseCandidate candidate,
            Instant now,
            boolean recovered) {
        ActiveReleasePointer pointer = candidate.pointer();
        VerifiedReleaseSnapshot release = cache.find(pointer.releaseId())
                .map(existing -> requirePointerMatch(existing, pointer, scope))
                .orElseGet(() -> cache.put(verifier.verify(candidate.envelopeBytes(), pointer, scope)));
        LocalActiveReleaseRegistry.ActivationResult activation = registry.activate(scope, pointer, release, now);
        if (activation == LocalActiveReleaseRegistry.ActivationResult.STALE_IGNORED) {
            return new RefreshResult(Status.STALE_IGNORED, pointer, null);
        }
        recordReady(scope, pointer, now);
        return new RefreshResult(
                recovered ? Status.READY_RECOVERED : Status.READY,
                pointer,
                null);
    }

    private VerifiedReleaseSnapshot requirePointerMatch(
            VerifiedReleaseSnapshot existing,
            ActiveReleasePointer pointer,
            ReleaseScope scope) {
        if (!existing.scope().equals(scope)
                || !existing.checksum().equals(pointer.snapshotChecksum())
                || !existing.signatureKeyId().equals(pointer.signatureKeyId())) {
            throw failure(SnapshotVerificationException.Reason.IMMUTABLE_RELEASE_CONFLICT,
                    "Cached immutable Release does not match Active Pointer");
        }
        return existing;
    }

    private void recordReady(ReleaseScope scope, ActiveReleasePointer pointer, Instant now) {
        try {
            acks.record(new ActivationAck(
                    scope,
                    properties.getRuntimeNodeId(),
                    pointer.releaseId(),
                    pointer.activationVersion(),
                    ActivationAck.Status.READY,
                    null,
                    now));
        } catch (RuntimeException failure) {
            LOG.error("Runtime Release READY ACK failed: environment={}, app={}, activationVersion={}",
                    scope.environment(), scope.app(), pointer.activationVersion(), failure);
        }
    }

    private void recordFailed(
            ReleaseScope scope,
            ActiveReleasePointer pointer,
            SnapshotVerificationException.Reason reason,
            Instant now) {
        try {
            acks.record(new ActivationAck(
                    scope,
                    properties.getRuntimeNodeId(),
                    pointer.releaseId(),
                    pointer.activationVersion(),
                    ActivationAck.Status.FAILED,
                    reason.name(),
                    now));
        } catch (RuntimeException failure) {
            LOG.error("Runtime Release FAILED ACK failed: environment={}, app={}, activationVersion={}",
                    scope.environment(), scope.app(), pointer.activationVersion(), failure);
        }
    }

    private static SnapshotVerificationException failure(
            SnapshotVerificationException.Reason reason,
            String message) {
        return new SnapshotVerificationException(reason, message);
    }

    private static SnapshotVerificationException failure(
            SnapshotVerificationException.Reason reason,
            String message,
            Throwable cause) {
        return new SnapshotVerificationException(reason, message, cause);
    }

    public record RefreshResult(
            Status status,
            ActiveReleasePointer pointer,
            SnapshotVerificationException.Reason failureReason) {
    }

    public enum Status {
        READY,
        READY_RECOVERED,
        FAILED_LKG_RETAINED,
        STALE_IGNORED
    }
}
