package com.xuntian.mock.client.config;

import com.xuntian.mock.client.core.routing.RoutingSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VerifiedConfigActivation {

    private final ActivationMetadata metadata;
    private final RoutingSnapshot snapshot;
    private final List<SdkSecurityPolicyRef> policyReferences;

    VerifiedConfigActivation(
            ActivationMetadata metadata,
            RoutingSnapshot snapshot,
            List<SdkSecurityPolicyRef> policyReferences) {
        this.metadata = metadata;
        this.snapshot = snapshot;
        this.policyReferences = Collections.unmodifiableList(
                new ArrayList<SdkSecurityPolicyRef>(policyReferences));
    }

    ActivationMetadata metadata() {
        return metadata;
    }

    RoutingSnapshot snapshot() {
        return snapshot;
    }

    List<SdkSecurityPolicyRef> policyReferences() {
        return policyReferences;
    }
}
