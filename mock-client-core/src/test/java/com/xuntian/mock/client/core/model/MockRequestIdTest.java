package com.xuntian.mock.client.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockRequestIdTest {

    @Test
    void generatesDistinctTraceableIds() {
        String first = MockRequestId.generate();
        String second = MockRequestId.generate();

        assertThat(first).startsWith("mr-");
        assertThat(second).startsWith("mr-").isNotEqualTo(first);
    }
}
