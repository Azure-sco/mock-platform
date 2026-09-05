package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CompiledJsonPath {

    private final String source;
    private final List<PathStep> steps;

    private CompiledJsonPath(String source, List<PathStep> steps) {
        this.source = source;
        this.steps = List.copyOf(steps);
    }

    public static CompiledJsonPath compile(String source) {
        if (source == null || source.isBlank() || source.charAt(0) != '$') {
            throw new IllegalArgumentException("JSON path must start with $");
        }
        List<PathStep> steps = new ArrayList<>();
        int index = 1;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '.') {
                int start = ++index;
                while (index < source.length() && isNameCharacter(source.charAt(index))) {
                    index++;
                }
                if (start == index) {
                    throw invalid(source);
                }
                steps.add(new FieldStep(source.substring(start, index)));
            } else if (current == '[') {
                int close = source.indexOf(']', index + 1);
                if (close < 0 || close == index + 1) {
                    throw invalid(source);
                }
                String number = source.substring(index + 1, close);
                if (!number.chars().allMatch(Character::isDigit)) {
                    throw invalid(source);
                }
                try {
                    steps.add(new IndexStep(Integer.parseInt(number)));
                } catch (NumberFormatException failure) {
                    throw invalid(source);
                }
                index = close + 1;
            } else {
                throw invalid(source);
            }
        }
        return new CompiledJsonPath(source, steps);
    }

    public Optional<JsonNode> read(JsonNode root) {
        if (root == null) {
            return Optional.empty();
        }
        JsonNode current = root;
        for (PathStep step : steps) {
            current = step.read(current);
            if (current == null || current.isMissingNode()) {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    public String source() {
        return source;
    }

    private static boolean isNameCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-';
    }

    private static IllegalArgumentException invalid(String source) {
        return new IllegalArgumentException("Unsupported JSON path: " + source);
    }

    private sealed interface PathStep permits FieldStep, IndexStep {
        JsonNode read(JsonNode node);
    }

    private record FieldStep(String name) implements PathStep {
        @Override
        public JsonNode read(JsonNode node) {
            return node.isObject() ? node.path(name) : null;
        }
    }

    private record IndexStep(int index) implements PathStep {
        @Override
        public JsonNode read(JsonNode node) {
            return node.isArray() ? node.path(index) : null;
        }
    }
}
