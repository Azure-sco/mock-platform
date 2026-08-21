package com.xuntian.mock.client.apollo;

import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.config.MockConfigListener;
import com.xuntian.mock.client.config.MockConfigProvider;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;

public final class ApolloConfigAdapter implements MockConfigProvider {

    private final LocalConfigProvider snapshots;

    public ApolloConfigAdapter(RoutingSnapshot initial) {
        this.snapshots = new LocalConfigProvider(initial);
    }

    public void onValidatedSnapshot(RoutingSnapshot snapshot) {
        snapshots.update(snapshot);
    }

    @Override
    public RoutingSnapshot current() {
        return snapshots.current();
    }

    @Override
    public void registerListener(MockConfigListener listener) {
        snapshots.registerListener(listener);
    }
}
