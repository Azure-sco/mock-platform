package com.xuntian.mock.client.boot2;

import com.xuntian.mock.client.annotation.ThirdPartyMock;
import com.xuntian.mock.client.config.MockConfigProvider;
import com.xuntian.mock.client.core.context.MockContext;
import com.xuntian.mock.client.core.context.MockContextHolder;
import com.xuntian.mock.client.core.model.MockRequestId;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

@Aspect
public final class ThirdPartyMockAspect {

    private final MockConfigProvider configProvider;
    private final MockClientProperties properties;

    public ThirdPartyMockAspect(MockConfigProvider configProvider, MockClientProperties properties) {
        this.configProvider = configProvider;
        this.properties = properties;
    }

    @Around("@annotation(thirdPartyMock)")
    public Object around(ProceedingJoinPoint joinPoint, ThirdPartyMock thirdPartyMock) throws Throwable {
        RoutingSnapshot snapshot = configProvider.current();
        String requestId = MockRequestId.generate();
        String traceId = hasText(properties.getTraceId()) ? properties.getTraceId() : requestId;
        MockContext context = MockContext.builder()
                .appCode(snapshot.appCode())
                .environment(snapshot.environment())
                .provider(thirdPartyMock.provider())
                .api(thirdPartyMock.api())
                .tenant(properties.getTenant())
                .testAccount(properties.getTestAccount())
                .traceId(traceId)
                .businessNo(properties.getBusinessNo())
                .mockRequestId(requestId)
                .requestOverrideMock(properties.isRequestOverrideMock())
                .build();
        try (MockContextHolder.Scope ignored = MockContextHolder.push(context)) {
            return joinPoint.proceed();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
