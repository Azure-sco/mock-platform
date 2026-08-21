package com.xuntian.mock.client.core.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public final class MockContextHolder {

    private static final ThreadLocal<Deque<MockContext>> CONTEXTS = new ThreadLocal<Deque<MockContext>>() {
        @Override
        protected Deque<MockContext> initialValue() {
            return new ArrayDeque<MockContext>();
        }
    };

    private MockContextHolder() {
    }

    public static Scope push(MockContext context) {
        CONTEXTS.get().push(context);
        return new Scope();
    }

    public static Optional<MockContext> current() {
        Deque<MockContext> contexts = CONTEXTS.get();
        return contexts.isEmpty() ? Optional.<MockContext>empty() : Optional.of(contexts.peek());
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            Deque<MockContext> contexts = CONTEXTS.get();
            contexts.pop();
            if (contexts.isEmpty()) {
                CONTEXTS.remove();
            }
            closed = true;
        }
    }
}
