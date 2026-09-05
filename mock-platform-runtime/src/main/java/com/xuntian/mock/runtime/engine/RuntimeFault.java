package com.xuntian.mock.runtime.engine;

/** Immutable transport-fault decision compiled from a published Scenario. */
public record RuntimeFault(Type type, long durationMs, SideEffectPolicy sideEffectPolicy) {

    public static final long MAX_DURATION_MS = 60_000;

    public RuntimeFault {
        if (type == null || sideEffectPolicy == null) {
            throw new IllegalArgumentException("Fault type and sideEffectPolicy are required");
        }
        if (durationMs < 0 || durationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException("Fault durationMs must be from 0 to 60000");
        }
        if (type == Type.NONE && durationMs != 0) {
            throw new IllegalArgumentException("NONE fault durationMs must be zero");
        }
        if (type == Type.CONNECTION_RESET && durationMs != 0) {
            throw new IllegalArgumentException("CONNECTION_RESET durationMs must be zero");
        }
    }

    public static RuntimeFault none() {
        return new RuntimeFault(Type.NONE, 0, SideEffectPolicy.APPLY_BEFORE_FAULT);
    }

    public boolean enabled() {
        return type != Type.NONE;
    }

    public enum Type { NONE, HTTP_ERROR, READ_TIMEOUT, CONNECTION_RESET }

    public enum SideEffectPolicy { NO_APPLY, APPLY_BEFORE_FAULT }
}
