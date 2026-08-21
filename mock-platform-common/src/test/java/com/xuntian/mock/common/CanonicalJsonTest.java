package com.xuntian.mock.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonTest {

    @Test
    void producesStableKeyOrderAndChecksum() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", 1);
        first.put("a", "value");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", "value");
        second.put("z", 1);

        byte[] firstBytes = CanonicalJson.write(first);
        byte[] secondBytes = CanonicalJson.write(second);

        assertThat(new String(firstBytes, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"a\":\"value\",\"z\":1}");
        assertThat(Checksum.sha256Hex(firstBytes)).isEqualTo(Checksum.sha256Hex(secondBytes));
    }
}
