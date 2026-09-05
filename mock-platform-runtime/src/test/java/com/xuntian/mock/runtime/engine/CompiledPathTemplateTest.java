package com.xuntian.mock.runtime.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompiledPathTemplateTest {

    private final CompiledPathTemplate template = CompiledPathTemplate.compile("/contracts/{id}");

    @Test
    void decodesEachSegmentExactlyOnce() {
        assertThat(template.match("/contracts/A%20B").orElseThrow()).containsEntry("id", "A B");
        assertThat(template.match("/contracts/%252e").orElseThrow()).containsEntry("id", "%2e");
    }

    @Test
    void rejectsTraversalSeparatorsAndInvalidEncoding() {
        assertThatThrownBy(() -> template.match("/contracts/%2e%2e"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.match("/contracts/%2Fadmin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.match("/contracts/%GG"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.match("/contracts/a%5Cb"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExtraSegments() {
        assertThat(template.match("/contracts/one/two")).isEmpty();
    }
}
