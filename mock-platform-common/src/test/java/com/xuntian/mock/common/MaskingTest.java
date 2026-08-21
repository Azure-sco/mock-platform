package com.xuntian.mock.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTest {

    @Test
    void masksSensitiveValuesWithoutLeakingShortValues() {
        assertThat(Masking.mask("abcdefghijkl")).isEqualTo("ab********kl");
        assertThat(Masking.mask("abc")).isEqualTo("***");
        assertThat(Masking.mask(null)).isNull();
    }
}
