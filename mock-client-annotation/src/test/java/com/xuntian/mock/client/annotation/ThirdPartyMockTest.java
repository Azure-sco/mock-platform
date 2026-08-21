package com.xuntian.mock.client.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ThirdPartyMockTest {

    @Test
    void exposesProviderAndApiAtRuntimeOnMethods() throws Exception {
        Method method = AnnotatedClient.class.getDeclaredMethod("create");

        ThirdPartyMock annotation = method.getAnnotation(ThirdPartyMock.class);

        assertThat(annotation.provider()).isEqualTo("CPS_EQB");
        assertThat(annotation.api()).isEqualTo("CPS_SIGN_CREATE_START");
        assertThat(ThirdPartyMock.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(ThirdPartyMock.class.getAnnotation(Target.class).value()).containsExactly(ElementType.METHOD);
    }

    private static class AnnotatedClient {
        @ThirdPartyMock(provider = "CPS_EQB", api = "CPS_SIGN_CREATE_START")
        void create() {
        }
    }
}
