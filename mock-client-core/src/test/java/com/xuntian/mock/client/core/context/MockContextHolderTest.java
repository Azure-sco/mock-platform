package com.xuntian.mock.client.core.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockContextHolderTest {

    @AfterEach
    void assertContextWasCleaned() {
        assertThat(MockContextHolder.current()).isEmpty();
    }

    @Test
    void nestedScopesRestoreTheOuterContext() {
        MockContext outer = context("outer");
        MockContext inner = context("inner");

        try (MockContextHolder.Scope ignored = MockContextHolder.push(outer)) {
            assertThat(MockContextHolder.current()).containsSame(outer);
            try (MockContextHolder.Scope nested = MockContextHolder.push(inner)) {
                assertThat(MockContextHolder.current()).containsSame(inner);
            }
            assertThat(MockContextHolder.current()).containsSame(outer);
        }
    }

    @Test
    void exceptionDoesNotLeakContext() {
        assertThatThrownBy(() -> {
            try (MockContextHolder.Scope ignored = MockContextHolder.push(context("failing"))) {
                throw new IllegalStateException("boom");
            }
        }).isInstanceOf(IllegalStateException.class);
    }

    private MockContext context(String requestId) {
        return MockContext.builder()
                .appCode("sample-app")
                .environment("TEST")
                .provider("OA")
                .api("OA_SETTLE_CREATE")
                .mockRequestId(requestId)
                .build();
    }
}
