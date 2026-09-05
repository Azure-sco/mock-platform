package com.xuntian.mock.client.config;

import com.xuntian.mock.client.core.routing.RoutingSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuration-source-neutral consumer for the signed activation wrapper. Verification and route
 * compilation finish before the immutable snapshot is atomically made visible to request threads.
 */
public final class SignedConfigActivationProvider implements MockConfigProvider {

    private final String appCode;
    private final String environment;
    private final String sdkInstanceId;
    private final LocalConfigProvider snapshots;
    private final SignedConfigActivationVerifier verifier;
    private final SdkConfigAckReporter acknowledgements;
    private final Clock clock;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicLong acknowledgementDispatchFailures = new AtomicLong();
    private final AtomicLong activationIdConflicts = new AtomicLong();
    private final Map<String, ProcessingReceipt> receipts = new LinkedHashMap<String, ProcessingReceipt>();
    private final Map<String, String> activationFingerprints = new HashMap<String, String>();

    public SignedConfigActivationProvider(
            String appCode,
            String environment,
            String sdkInstanceId,
            RoutingSnapshot initialRealSnapshot,
            ConfigSignatureKeyProvider keyProvider,
            SdkConfigAckReporter acknowledgements,
            Clock clock) {
        this.appCode = requireSafe(appCode, 128, "appCode");
        this.environment = requireSafe(environment, 32, "environment");
        this.sdkInstanceId = requireSafe(sdkInstanceId, 128, "sdkInstanceId");
        this.snapshots = new LocalConfigProvider(Objects.requireNonNull(initialRealSnapshot, "initialRealSnapshot"));
        if (!this.appCode.equals(initialRealSnapshot.appCode())
                || !this.environment.equals(initialRealSnapshot.environment())) {
            throw new IllegalArgumentException("initial snapshot scope does not match provider scope");
        }
        if (!initialRealSnapshot.containsOnlyRealRoutes()) {
            throw new IllegalArgumentException("initial snapshot must contain REAL routes only");
        }
        this.verifier = new SignedConfigActivationVerifier(keyProvider);
        this.acknowledgements = Objects.requireNonNull(acknowledgements, "acknowledgements");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Applies raw UTF-8 JSON delivered by a configuration adapter. */
    public synchronized ConfigApplyResult onConfigChanged(byte[] signedActivationWrapper) {
        String fingerprint = fingerprint(signedActivationWrapper);
        ProcessingReceipt replay = receipts.get(fingerprint);
        if (replay != null) {
            if (replay.acknowledgement != null) {
                report(replay.acknowledgement);
            }
            return replay.result;
        }
        RoutingSnapshot previous = snapshots.current();
        Instant now = clock.instant();
        try {
            VerifiedConfigActivation verified = verifier.verify(
                    signedActivationWrapper,
                    appCode,
                    environment,
                    previous.configVersion(),
                    now);
            String existingFingerprint = activationFingerprints.get(verified.metadata().activationId());
            if (existingFingerprint != null && !existingFingerprint.equals(fingerprint)) {
                snapshots.markInvalid(verified.snapshot().configVersion());
                return reject(
                        verified.metadata(),
                        "ACTIVATION_ID_REUSED",
                        previous.configVersion(),
                        now,
                        verified.policyReferences(),
                        fingerprint);
            }
            try {
                snapshots.update(verified.snapshot());
            } catch (IllegalArgumentException concurrentNewerVersion) {
                return reject(
                        verified.metadata(),
                        "CONFIG_VERSION_RACE",
                        previous.configVersion(),
                        now,
                        verified.policyReferences(),
                        fingerprint);
            }
            ready.set(true);
            SdkConfigAck acknowledgement = new SdkConfigAck(
                    verified.metadata().activationId(),
                    appCode,
                    environment,
                    sdkInstanceId,
                    envelopeId(verified.metadata()),
                    previous.configVersion(),
                    verified.snapshot().configVersion(),
                    verified.policyReferences(),
                    SdkConfigAck.Status.APPLIED,
                    now,
                    null);
            ConfigApplyResult result = ConfigApplyResult.applied(
                    verified.metadata().activationId(),
                    verified.snapshot().configVersion());
            remember(fingerprint, verified.metadata().activationId(), result, acknowledgement);
            report(acknowledgement);
            return result;
        } catch (ConfigActivationException rejected) {
            ActivationMetadata metadata = rejected.metadata();
            RoutingSnapshot current = snapshots.current();
            if (appCode.equals(metadata.appCode())
                    && environment.equals(metadata.environment())
                    && metadata.configVersion() > current.configVersion()) {
                snapshots.markInvalid(metadata.configVersion());
            }
            return reject(
                    metadata,
                    rejected.errorCode(),
                    current.configVersion(),
                    now,
                    Collections.<SdkSecurityPolicyRef>emptyList(),
                    fingerprint);
        }
    }

    /** False for a new instance until at least one valid signed activation has been applied. */
    public boolean isReady() {
        RoutingSnapshot current = snapshots.current();
        return ready.get()
                && current.mockConfigValid()
                && (current.mockConfigExpiresAt() == null
                || clock.instant().isBefore(current.mockConfigExpiresAt()));
    }

    public long acknowledgementDispatchFailureCount() {
        return acknowledgementDispatchFailures.get();
    }

    public long activationIdConflictCount() {
        return activationIdConflicts.get();
    }

    @Override
    public RoutingSnapshot current() {
        return snapshots.current();
    }

    @Override
    public void registerListener(MockConfigListener listener) {
        snapshots.registerListener(listener);
    }

    private ConfigApplyResult reject(
            ActivationMetadata metadata,
            String errorCode,
            long oldVersion,
            Instant now,
            java.util.List<SdkSecurityPolicyRef> policyReferences,
            String fingerprint) {
        SdkConfigAck acknowledgement = null;
        String existingFingerprint = metadata.activationId() == null
                ? null
                : activationFingerprints.get(metadata.activationId());
        if (metadata.activationId() != null
                && envelopeId(metadata) > 0L
                && metadata.configVersion() > 0L
                && appCode.equals(metadata.appCode())
                && environment.equals(metadata.environment())
                && (existingFingerprint == null || existingFingerprint.equals(fingerprint))) {
            acknowledgement = new SdkConfigAck(
                    metadata.activationId(),
                    appCode,
                    environment,
                    sdkInstanceId,
                    envelopeId(metadata),
                    oldVersion,
                    metadata.configVersion(),
                    policyReferences,
                    SdkConfigAck.Status.REJECTED,
                    now,
                    errorCode);
        } else if (existingFingerprint != null && !existingFingerprint.equals(fingerprint)) {
            activationIdConflicts.incrementAndGet();
        }
        ConfigApplyResult result = ConfigApplyResult.rejected(
                metadata.activationId(),
                metadata.configVersion(),
                errorCode);
        remember(fingerprint, metadata.activationId(), result, acknowledgement);
        if (acknowledgement != null) {
            report(acknowledgement);
        }
        return result;
    }

    private void report(SdkConfigAck acknowledgement) {
        try {
            acknowledgements.report(acknowledgement);
        } catch (RuntimeException dispatcherUnavailable) {
            acknowledgementDispatchFailures.incrementAndGet();
        }
    }

    private static String requireSafe(String value, int maxLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            boolean safe = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.'
                    || character == '_'
                    || character == '-'
                    || character == ':';
            if (!safe) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
        return value;
    }

    private String fingerprint(byte[] wrapper) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) (wrapper == null ? 0 : 1));
            if (wrapper != null) {
                digest.update(wrapper);
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private long envelopeId(ActivationMetadata metadata) {
        if (metadata.envelopeId() == null) {
            return -1L;
        }
        try {
            return Long.parseLong(metadata.envelopeId());
        } catch (NumberFormatException invalid) {
            return -1L;
        }
    }

    private void remember(
            String fingerprint,
            String activationId,
            ConfigApplyResult result,
            SdkConfigAck acknowledgement) {
        if (receipts.size() >= 256) {
            String oldest = receipts.keySet().iterator().next();
            ProcessingReceipt evicted = receipts.remove(oldest);
            if (evicted.activationId != null
                    && oldest.equals(activationFingerprints.get(evicted.activationId))) {
                activationFingerprints.remove(evicted.activationId);
            }
        }
        receipts.put(fingerprint, new ProcessingReceipt(activationId, result, acknowledgement));
        if (activationId != null && !activationFingerprints.containsKey(activationId)) {
            activationFingerprints.put(activationId, fingerprint);
        }
    }

    private static final class ProcessingReceipt {
        private final String activationId;
        private final ConfigApplyResult result;
        private final SdkConfigAck acknowledgement;

        private ProcessingReceipt(
                String activationId,
                ConfigApplyResult result,
                SdkConfigAck acknowledgement) {
            this.activationId = activationId;
            this.result = result;
            this.acknowledgement = acknowledgement;
        }
    }
}
