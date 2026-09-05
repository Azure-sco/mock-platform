package com.xuntian.mock.runtime.admission;

public final class AdmissionVerificationException extends RuntimeException {

    private final Reason reason;

    public AdmissionVerificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AdmissionVerificationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_ENVELOPE,
        CHECKSUM_MISMATCH,
        SIGNATURE_INVALID,
        UNKNOWN_KEY,
        SCOPE_MISMATCH,
        CLOCK_SKEW,
        EXPIRED,
        PAYLOAD_INVALID
    }
}
