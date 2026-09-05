package com.xuntian.mock.client.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class CanonicalJson {

    private final ObjectMapper mapper;

    CanonicalJson() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        this.mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.mapper.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
    }

    JsonNode parse(byte[] json) throws IOException {
        return mapper.readTree(json);
    }

    <T> T convert(JsonNode node, Class<T> type) throws IOException {
        return mapper.treeToValue(node, type);
    }

    byte[] bytes(JsonNode node) throws IOException {
        return mapper.writeValueAsBytes(sorted(node));
    }

    private JsonNode sorted(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode sorted = mapper.createArrayNode();
            for (JsonNode item : node) {
                sorted.add(sorted(item));
            }
            return sorted;
        }
        ObjectNode sorted = mapper.createObjectNode();
        List<String> names = new ArrayList<String>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            names.add(fields.next().getKey());
        }
        Collections.sort(names);
        for (String name : names) {
            sorted.set(name, sorted(node.get(name)));
        }
        return sorted;
    }
}
