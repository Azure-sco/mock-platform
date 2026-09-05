package com.xuntian.mock.runtime.release;

import java.time.Instant;

public record ActivationAck(
        ReleaseScope scope,
        String runtimeNodeId,
        String releaseId,
        long activationVersion,
        Status status,
        String errorMasked,
        Instant reportedAt) {

    public ActivationAck {
        if (scope == null || status == null || reportedAt == null
                || !safe(runtimeNodeId, 128) || !safe(releaseId, 64)
                || activationVersion < 1) {
            throw new IllegalArgumentException("Activation ACK is invalid");
        }
        if (status == Status.READY && errorMasked != null) {
            throw new IllegalArgumentException("READY Activation ACK cannot contain an error");
        }
        if (errorMasked != null && (errorMasked.length() > 128
                || errorMasked.contains("\r") || errorMasked.contains("\n"))) {
            throw new IllegalArgumentException("Activation ACK error is invalid");
        }
    }

    private static boolean safe(String value, int maxLength) {
        return value != null && value.length() <= maxLength
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }

    public enum Status {
        READY,
        FAILED
    }
}
