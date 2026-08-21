package com.xuntian.mock.client.config;

import com.xuntian.mock.client.core.routing.RoutingSnapshot;

public interface MockConfigProvider {

    RoutingSnapshot current();

    void registerListener(MockConfigListener listener);
}
