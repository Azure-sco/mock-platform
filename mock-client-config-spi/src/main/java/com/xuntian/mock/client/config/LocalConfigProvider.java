package com.xuntian.mock.client.config;

import com.xuntian.mock.client.core.routing.RoutingSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalConfigProvider implements MockConfigProvider {

    private final AtomicReference<RoutingSnapshot> current;
    private final List<MockConfigListener> listeners = new CopyOnWriteArrayList<MockConfigListener>();
    private final AtomicLong listenerFailures = new AtomicLong();

    public LocalConfigProvider(RoutingSnapshot initial) {
        this.current = new AtomicReference<RoutingSnapshot>(Objects.requireNonNull(initial, "initial"));
    }

    @Override
    public RoutingSnapshot current() {
        return current.get();
    }

    @Override
    public void registerListener(MockConfigListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void update(RoutingSnapshot next) {
        Objects.requireNonNull(next, "next");
        RoutingSnapshot previous;
        do {
            previous = current.get();
            if (next.configVersion() <= previous.configVersion()) {
                throw new IllegalArgumentException("configVersion must increase");
            }
        } while (!current.compareAndSet(previous, next));

        notifyListeners(previous, next);
    }

    /**
     * Keeps the last known routing table while disabling its MOCK/CANARY routes. A later verified
     * snapshot only has to be newer than the active version, so a clean re-delivery of the rejected
     * observed version can recover the provider.
     */
    public boolean markInvalid(long observedConfigVersion) {
        RoutingSnapshot previous;
        RoutingSnapshot invalid;
        do {
            previous = current.get();
            if (observedConfigVersion <= previous.configVersion()
                    || (!previous.mockConfigValid()
                    && observedConfigVersion <= previous.observedConfigVersion())) {
                return false;
            }
            invalid = RoutingSnapshot.builderFrom(previous)
                    .mockConfigValid(false)
                    .observedConfigVersion(observedConfigVersion)
                    .build();
        } while (!current.compareAndSet(previous, invalid));

        notifyListeners(previous, invalid);
        return true;
    }

    private void notifyListeners(RoutingSnapshot previous, RoutingSnapshot next) {
        for (MockConfigListener listener : listeners) {
            try {
                listener.onChanged(previous, next);
            } catch (RuntimeException listenerFailure) {
                listenerFailures.incrementAndGet();
            }
        }
    }

    public long listenerFailureCount() {
        return listenerFailures.get();
    }
}
