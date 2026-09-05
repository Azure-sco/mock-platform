package com.xuntian.mock.runtime.release;

public final class SnapshotVerificationException extends RuntimeException {

    private final Reason reason;

    public SnapshotVerificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SnapshotVerificationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_ENVELOPE,
        UNSUPPORTED_SCHEMA,
        SCOPE_MISMATCH,
        CHECKSUM_MISMATCH,
        UNKNOWN_KEY,
        SIGNATURE_INVALID,
        COMPILE_FAILED,
        IMMUTABLE_RELEASE_CONFLICT,
        POINTER_INVALID,
        SOURCE_UNAVAILABLE
    }
}
