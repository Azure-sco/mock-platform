package com.xuntian.mock.client.boot3;

import com.xuntian.mock.client.config.MockConfigProvider;
import feign.Client;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.client.RestTemplate;

public final class MockTransportBeanPostProcessor implements BeanPostProcessor {

    private final MockConfigProvider configProvider;
    private final String mockAppToken;

    public MockTransportBeanPostProcessor(MockConfigProvider configProvider, String mockAppToken) {
        this.configProvider = configProvider;
        this.mockAppToken = mockAppToken;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof Client client && !(client instanceof MockFeignClient)) {
            return new MockFeignClient(client, configProvider, mockAppToken);
        }
        if (bean instanceof RestTemplate restTemplate
                && !(restTemplate.getRequestFactory() instanceof MockClientHttpRequestFactory)) {
            restTemplate.setRequestFactory(new MockClientHttpRequestFactory(
                    restTemplate.getRequestFactory(), configProvider, mockAppToken));
        }
        return bean;
    }
}
