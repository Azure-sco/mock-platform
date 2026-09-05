package com.xuntian.mock.runtime.admission;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.identity.RuntimeIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!local & !test")
public final class AdmissionSnapshotRegistry implements AdmissionAuthorizer {

    private final ConcurrentHashMap<AdmissionScope, VerifiedAdmissionSnapshot> snapshots = new ConcurrentHashMap<>();

    public ApplyResult apply(VerifiedAdmissionSnapshot candidate) {
        ApplyResult[] result = {ApplyResult.APPLIED};
        snapshots.compute(candidate.scope(), (scope, current) -> {
            if (current == null) return candidate;
            if (candidate.bindingVersion() < current.bindingVersion()
                    || candidate.bindingVersion() == current.bindingVersion()
                    && !candidate.issuedAt().isAfter(current.issuedAt())) {
                result[0] = ApplyResult.STALE_IGNORED;
                return current;
            }
            if (candidate.bindingVersion() == current.bindingVersion()
                    && (!candidate.bindingId().equals(current.bindingId())
                    || candidate.policyVersionId() != current.policyVersionId())) {
                throw new AdmissionVerificationException(
                        AdmissionVerificationException.Reason.PAYLOAD_INVALID,
                        "Admission renewal changed immutable binding contents");
            }
            return candidate;
        });
        return result[0];
    }

    public Optional<VerifiedAdmissionSnapshot> current(AdmissionScope scope) {
        return Optional.ofNullable(snapshots.get(scope));
    }

    @Override
    public void authorize(RuntimeIdentity identity, String providerCode, String apiCode, Instant now) {
        AdmissionScope scope = new AdmissionScope(identity.environment(), identity.appCode());
        VerifiedAdmissionSnapshot snapshot = snapshots.get(scope);
        if (snapshot == null || !snapshot.notAfter().isAfter(now)) {
            throw new PlatformException(
                    ErrorCode.MOCK_ADMISSION_POLICY_STALE,
                    "Active Admission policy is unavailable or expired");
        }
        if (!snapshot.allows(providerCode, apiCode, identity.tenantCode(), identity.testAccount())) {
            throw new PlatformException(ErrorCode.MOCK_FORBIDDEN, "Mock request is denied by Active Admission policy");
        }
    }

    public enum ApplyResult {
        APPLIED,
        STALE_IGNORED
    }
}
