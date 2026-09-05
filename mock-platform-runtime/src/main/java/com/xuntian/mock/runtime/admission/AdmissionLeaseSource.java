package com.xuntian.mock.runtime.admission;

import java.util.Optional;

public interface AdmissionLeaseSource {

    Optional<byte[]> load(AdmissionScope scope);
}
