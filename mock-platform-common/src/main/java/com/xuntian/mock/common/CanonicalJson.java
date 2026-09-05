package com.xuntian.mock.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CanonicalJson() {
    }

    public static byte[] write(Object value) {
        try {
            JsonNode tree = value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
            return MAPPER.writeValueAsBytes(sorted(tree));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Value cannot be serialized as canonical JSON", failure);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(item -> result.add(sorted(item)));
            return result;
        }
        ObjectNode result = MAPPER.createObjectNode();
        List<String> names = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) names.add(fields.next().getKey());
        Collections.sort(names);
        names.forEach(name -> result.set(name, sorted(node.get(name))));
        return result;
    }
}
