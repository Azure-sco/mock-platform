package com.xuntian.mock.runtime.admission;

import com.xuntian.mock.runtime.identity.RuntimeIdentity;

import java.time.Instant;

public interface AdmissionAuthorizer {

    void authorize(RuntimeIdentity identity, String providerCode, String apiCode, Instant now);
}
