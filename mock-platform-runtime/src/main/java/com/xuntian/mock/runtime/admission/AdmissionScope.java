package com.xuntian.mock.runtime.admission;

public record AdmissionScope(String environment, String appCode) {

    public AdmissionScope {
        if (environment == null || environment.isBlank() || appCode == null || appCode.isBlank()) {
            throw new IllegalArgumentException("Admission scope is required");
        }
    }
}
