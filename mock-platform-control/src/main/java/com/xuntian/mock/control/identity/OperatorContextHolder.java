package com.xuntian.mock.control.identity;

import java.util.Optional;

public final class OperatorContextHolder {

    private static final ThreadLocal<OperatorContext> CONTEXT = new ThreadLocal<>();

    private OperatorContextHolder() {
    }

    static void set(OperatorContext context) {
        CONTEXT.set(context);
    }

    public static Optional<OperatorContext> current() {
        return Optional.ofNullable(CONTEXT.get());
    }

    static void clear() {
        CONTEXT.remove();
    }
}
