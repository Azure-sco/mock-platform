package com.xuntian.mock.client.boot2;

import com.xuntian.mock.client.config.MockConfigProvider;
import feign.Capability;
import feign.Client;

public final class MockFeignCapability implements Capability {

    private final MockConfigProvider configProvider;
    private final String mockAppToken;

    public MockFeignCapability(MockConfigProvider configProvider, String mockAppToken) {
        this.configProvider = configProvider;
        this.mockAppToken = mockAppToken;
    }

    @Override
    public Client enrich(Client client) {
        if (client instanceof MockFeignClient) {
            return client;
        }
        return new MockFeignClient(client, configProvider, mockAppToken);
    }
}
