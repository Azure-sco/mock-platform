package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CompiledJsonSchema {

    private static final CompiledJsonSchema ANY = new CompiledJsonSchema(
            JsonType.ANY, Set.of(), Map.of(), null, List.of(), null, null, null);

    private final JsonType type;
    private final Set<String> required;
    private final Map<String, CompiledJsonSchema> properties;
    private final CompiledJsonSchema items;
    private final List<String> enumValues;
    private final Integer maxLength;
    private final BigDecimal minimum;
    private final BigDecimal maximum;

    private CompiledJsonSchema(
            JsonType type,
            Set<String> required,
            Map<String, CompiledJsonSchema> properties,
            CompiledJsonSchema items,
            List<String> enumValues,
            Integer maxLength,
            BigDecimal minimum,
            BigDecimal maximum) {
        this.type = type;
        this.required = Set.copyOf(required);
        this.properties = Map.copyOf(properties);
        this.items = items;
        this.enumValues = List.copyOf(enumValues);
        this.maxLength = maxLength;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public static CompiledJsonSchema compile(JsonNode definition) {
        if (definition == null || definition.isNull() || definition.isMissingNode()) {
            return ANY;
        }
        if (!definition.isObject()) {
            throw new IllegalArgumentException("JSON schema must be an object");
        }
        JsonType type = JsonType.from(definition.path("type").asText("any"));
        Set<String> required = new LinkedHashSet<>();
        JsonNode requiredNode = definition.path("required");
        if (!requiredNode.isMissingNode()) {
            if (!requiredNode.isArray()) {
                throw new IllegalArgumentException("schema.required must be an array");
            }
            requiredNode.forEach(item -> {
                if (!item.isTextual() || item.textValue().isBlank()) {
                    throw new IllegalArgumentException("schema.required entries must be non-blank strings");
                }
                required.add(item.textValue());
            });
        }
        Map<String, CompiledJsonSchema> properties = new LinkedHashMap<>();
        JsonNode propertyNode = definition.path("properties");
        if (!propertyNode.isMissingNode()) {
            if (!propertyNode.isObject()) {
                throw new IllegalArgumentException("schema.properties must be an object");
            }
            propertyNode.fields().forEachRemaining(entry ->
                    properties.put(entry.getKey(), compile(entry.getValue())));
        }
        CompiledJsonSchema items = definition.has("items") ? compile(definition.get("items")) : null;
        List<String> enumValues = new ArrayList<>();
        if (definition.has("enum")) {
            if (!definition.get("enum").isArray()) {
                throw new IllegalArgumentException("schema.enum must be an array");
            }
            definition.get("enum").forEach(value -> enumValues.add(value.toString()));
        }
        Integer maxLength = definition.has("maxLength") ? definition.get("maxLength").asInt() : null;
        BigDecimal minimum = definition.has("minimum") ? definition.get("minimum").decimalValue() : null;
        BigDecimal maximum = definition.has("maximum") ? definition.get("maximum").decimalValue() : null;
        if (maxLength != null && maxLength < 0) {
            throw new IllegalArgumentException("schema.maxLength cannot be negative");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("schema.minimum cannot exceed maximum");
        }
        return new CompiledJsonSchema(
                type, required, properties, items, enumValues, maxLength, minimum, maximum);
    }

    public void validate(JsonNode value) {
        validate(value, "$");
    }

    private void validate(JsonNode value, String path) {
        if (!type.matches(value)) {
            throw new IllegalArgumentException(path + " must be " + type.externalName);
        }
        if (!enumValues.isEmpty() && !enumValues.contains(value.toString())) {
            throw new IllegalArgumentException(path + " is not an allowed value");
        }
        if (value.isObject()) {
            for (String name : required) {
                if (!value.has(name)) {
                    throw new IllegalArgumentException(path + "." + name + " is required");
                }
            }
            properties.forEach((name, schema) -> {
                if (value.has(name)) {
                    schema.validate(value.get(name), path + "." + name);
                }
            });
        } else if (value.isArray() && items != null) {
            for (int index = 0; index < value.size(); index++) {
                items.validate(value.get(index), path + "[" + index + "]");
            }
        } else if (value.isTextual() && maxLength != null && value.textValue().length() > maxLength) {
            throw new IllegalArgumentException(path + " exceeds maxLength");
        } else if (value.isNumber()) {
            BigDecimal number = value.decimalValue();
            if (minimum != null && number.compareTo(minimum) < 0) {
                throw new IllegalArgumentException(path + " is below minimum");
            }
            if (maximum != null && number.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(path + " exceeds maximum");
            }
        }
    }

    private enum JsonType {
        ANY("any") {
            @Override boolean matches(JsonNode value) { return true; }
        },
        OBJECT("object") {
            @Override boolean matches(JsonNode value) { return value != null && value.isObject(); }
        },
        ARRAY("array") {
            @Override boolean matches(JsonNode value) { return value != null && value.isArray(); }
        },
        STRING("string") {
            @Override boolean matches(JsonNode value) { return value != null && value.isTextual(); }
        },
        NUMBER("number") {
            @Override boolean matches(JsonNode value) { return value != null && value.isNumber(); }
        },
        INTEGER("integer") {
            @Override boolean matches(JsonNode value) { return value != null && value.isIntegralNumber(); }
        },
        BOOLEAN("boolean") {
            @Override boolean matches(JsonNode value) { return value != null && value.isBoolean(); }
        },
        NULL("null") {
            @Override boolean matches(JsonNode value) { return value == null || value.isNull(); }
        };

        private final String externalName;

        JsonType(String externalName) {
            this.externalName = externalName;
        }

        abstract boolean matches(JsonNode value);

        static JsonType from(String value) {
            for (JsonType type : values()) {
                if (type.externalName.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unsupported JSON schema type: " + value);
        }
    }
}
