package com.xuntian.mock.runtime.admission;

import java.time.Instant;

public interface AdmissionAckPort {

    void ready(VerifiedAdmissionSnapshot snapshot, String runtimeNodeId, Instant reportedAt);
}
