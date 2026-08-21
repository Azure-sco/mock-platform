package com.xuntian.mock.client.config;

import com.xuntian.mock.client.core.routing.RoutingSnapshot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalConfigProvider implements MockConfigProvider {

    private final AtomicReference<RoutingSnapshot> current;
    private final List<MockConfigListener> listeners = new CopyOnWriteArrayList<MockConfigListener>();

    public LocalConfigProvider(RoutingSnapshot initial) {
        this.current = new AtomicReference<RoutingSnapshot>(initial);
    }

    @Override
    public RoutingSnapshot current() {
        return current.get();
    }

    @Override
    public void registerListener(MockConfigListener listener) {
        listeners.add(listener);
    }

    public void update(RoutingSnapshot next) {
        RoutingSnapshot previous;
        do {
            previous = current.get();
            if (next.configVersion() <= previous.configVersion()) {
                throw new IllegalArgumentException("configVersion must increase");
            }
        } while (!current.compareAndSet(previous, next));

        for (MockConfigListener listener : listeners) {
            listener.onChanged(previous, next);
        }
    }
}
