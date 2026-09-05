package com.xuntian.mock.runtime.release;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalActiveReleaseRegistry {

    private final ConcurrentHashMap<ReleaseScope, ActiveRelease> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArchivedRelease> archived = new ConcurrentHashMap<>();
    private final Duration lastKnownGoodWindow;

    public LocalActiveReleaseRegistry(Duration lastKnownGoodWindow) {
        if (lastKnownGoodWindow == null || lastKnownGoodWindow.isNegative()
                || lastKnownGoodWindow.isZero() || lastKnownGoodWindow.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Last Known Good window must be between 0 and 10 minutes");
        }
        this.lastKnownGoodWindow = lastKnownGoodWindow;
    }

    public ActivationResult activate(
            ReleaseScope scope,
            ActiveReleasePointer pointer,
            VerifiedReleaseSnapshot release,
            Instant verifiedAt) {
        if (!scope.equals(release.scope()) || !pointer.releaseId().equals(release.releaseId())
                || !pointer.snapshotChecksum().equals(release.checksum())
                || !pointer.signatureKeyId().equals(release.signatureKeyId())) {
            throw new IllegalArgumentException("Active Pointer does not match verified Release");
        }
        archived.put(release.releaseId(), new ArchivedRelease(
                scope, pointer.activationVersion(), release));
        ActivationResult[] result = new ActivationResult[1];
        active.compute(scope, (ignored, current) -> {
            if (current != null && pointer.activationVersion() < current.pointer().activationVersion()) {
                result[0] = ActivationResult.STALE_IGNORED;
                return current;
            }
            if (current != null && pointer.activationVersion() == current.pointer().activationVersion()) {
                if (!current.pointer().equals(pointer)) {
                    throw new SnapshotVerificationException(
                            SnapshotVerificationException.Reason.POINTER_INVALID,
                            "Activation Version was observed with conflicting content");
                }
                result[0] = ActivationResult.CONFIRMED;
                return new ActiveRelease(pointer, current.release(), verifiedAt);
            }
            result[0] = ActivationResult.ACTIVATED;
            return new ActiveRelease(pointer, release, verifiedAt);
        });
        return result[0];
    }

    public Optional<PinnedRuntimeSnapshot> pin(ReleaseScope scope, Instant now) {
        ActiveRelease selected = active.get(scope);
        if (selected == null || now.isAfter(selected.verifiedAt().plus(lastKnownGoodWindow))) {
            return Optional.empty();
        }
        return Optional.of(new PinnedRuntimeSnapshot(
                selected.pointer().releaseId(),
                selected.pointer().activationVersion(),
                selected.release().checksum(),
                selected.release().snapshot()));
    }

    public Optional<ActiveReleasePointer> currentPointer(ReleaseScope scope) {
        ActiveRelease current = active.get(scope);
        return current == null ? Optional.empty() : Optional.of(current.pointer());
    }

    public Optional<PinnedRuntimeSnapshot> pinRelease(ReleaseScope scope, String releaseId) {
        ArchivedRelease selected = archived.get(releaseId);
        if (selected == null || !selected.scope().equals(scope)) return Optional.empty();
        return Optional.of(new PinnedRuntimeSnapshot(
                selected.release().releaseId(), selected.activationVersion(),
                selected.release().checksum(), selected.release().snapshot()));
    }

    public void retain(
            ReleaseScope scope,
            ActiveReleasePointer pointer,
            VerifiedReleaseSnapshot release) {
        if (!scope.equals(release.scope()) || !pointer.releaseId().equals(release.releaseId())
                || !pointer.snapshotChecksum().equals(release.checksum())
                || !pointer.signatureKeyId().equals(release.signatureKeyId())) {
            throw new IllegalArgumentException("Release Pointer does not match verified Release");
        }
        archived.compute(release.releaseId(), (ignored, current) -> {
            if (current != null && (!current.scope().equals(scope)
                    || current.activationVersion() != pointer.activationVersion()
                    || !current.release().checksum().equals(release.checksum()))) {
                throw new SnapshotVerificationException(
                        SnapshotVerificationException.Reason.POINTER_INVALID,
                        "Archived Release was observed with conflicting content");
            }
            return new ArchivedRelease(scope, pointer.activationVersion(), release);
        });
    }

    public enum ActivationResult {
        ACTIVATED,
        CONFIRMED,
        STALE_IGNORED
    }

    private record ActiveRelease(
            ActiveReleasePointer pointer,
            VerifiedReleaseSnapshot release,
            Instant verifiedAt) {
    }

    private record ArchivedRelease(
            ReleaseScope scope,
            long activationVersion,
            VerifiedReleaseSnapshot release) { }
}
