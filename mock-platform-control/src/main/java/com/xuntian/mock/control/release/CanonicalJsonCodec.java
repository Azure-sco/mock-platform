package com.xuntian.mock.control.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public final class CanonicalJsonCodec {

    private final ObjectMapper mapper;

    public CanonicalJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(sort(mapper.valueToTree(value)));
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Canonical JSON cannot be serialized", failure);
        }
    }

    public JsonNode read(byte[] value) {
        try {
            return mapper.readTree(value);
        } catch (IOException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Canonical JSON cannot be parsed", failure);
        }
    }

    public String text(byte[] value) {
        return new String(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode sort(JsonNode source) {
        if (source == null || source.isNull() || source.isValueNode()) return source;
        if (source.isArray()) {
            ArrayNode target = mapper.createArrayNode();
            source.forEach(item -> target.add(sort(item)));
            return target;
        }
        List<String> names = new ArrayList<>();
        source.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        ObjectNode target = mapper.createObjectNode();
        names.forEach(name -> target.set(name, sort(source.get(name))));
        return target;
    }
}
