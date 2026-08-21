package com.xuntian.mock.client.config;

import com.xuntian.mock.client.core.routing.RoutingSnapshot;

public interface MockConfigListener {

    void onChanged(RoutingSnapshot previous, RoutingSnapshot current);
}
