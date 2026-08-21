package com.xuntian.mock.client.boot2;

import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.config.MockConfigProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ThirdPartyMockAspect.class)
@ConditionalOnProperty(prefix = "xuntian.mock.client", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MockClientProperties.class)
public class MockClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MockConfigProvider.class)
    public MockConfigProvider mockConfigProvider(MockClientProperties properties) {
        return new LocalConfigProvider(properties.toSnapshot());
    }

    @Bean
    public ThirdPartyMockAspect thirdPartyMockAspect(
            MockConfigProvider configProvider,
            MockClientProperties properties) {
        return new ThirdPartyMockAspect(configProvider, properties);
    }

    @Bean
    public MockFeignCapability mockFeignCapability(
            MockConfigProvider configProvider,
            MockClientProperties properties) {
        return new MockFeignCapability(configProvider, properties.getMockAppToken());
    }

    @Bean
    public static MockTransportBeanPostProcessor mockTransportBeanPostProcessor(
            MockConfigProvider configProvider,
            MockClientProperties properties) {
        return new MockTransportBeanPostProcessor(configProvider, properties.getMockAppToken());
    }
}
