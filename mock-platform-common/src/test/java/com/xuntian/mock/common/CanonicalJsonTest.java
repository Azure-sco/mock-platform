package com.xuntian.mock.common;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    @Test
    void recursivelySortsObjectNodesWhilePreservingArrayOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var first = mapper.readTree("{\"outer\":{\"z\":1,\"a\":2},\"items\":[{\"y\":3,\"b\":4},1]}");
        var second = mapper.readTree("{\"items\":[{\"b\":4,\"y\":3},1],\"outer\":{\"a\":2,\"z\":1}}");

        assertThat(CanonicalJson.write(first)).containsExactly(CanonicalJson.write(second));
        assertThat(new String(CanonicalJson.write(first), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"items\":[{\"b\":4,\"y\":3},1],\"outer\":{\"a\":2,\"z\":1}}");
    }
}
