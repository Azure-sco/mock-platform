package com.xuntian.mock.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeysTest {

    @Test
    void createsNamespacedKeys() {
        assertThat(RedisKeys.key("m0", "gate")).isEqualTo("third-party-mock:m0:gate");
    }
}
