package com.xuntian.mock.runtime.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

public final class CompiledPathTemplate {

    private final String source;
    private final List<Segment> segments;

    private CompiledPathTemplate(String source, List<Segment> segments) {
        this.source = source;
        this.segments = List.copyOf(segments);
    }

    public static CompiledPathTemplate compile(String source) {
        if (source == null || !source.startsWith("/")) {
            throw new IllegalArgumentException("Path template must start with /");
        }
        List<String> values = split(source);
        List<Segment> segments = new ArrayList<>(values.size());
        Set<String> variableNames = new HashSet<>();
        for (String value : values) {
            if (value.startsWith("{") && value.endsWith("}") && value.length() > 2) {
                String name = value.substring(1, value.length() - 1);
                if (!name.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                    throw new IllegalArgumentException("Invalid path variable: " + value);
                }
                if (!variableNames.add(name)) {
                    throw new IllegalArgumentException("Duplicate path variable: " + name);
                }
                segments.add(new VariableSegment(name));
            } else if (value.contains("{") || value.contains("}")) {
                throw new IllegalArgumentException("Invalid path template segment: " + value);
            } else {
                segments.add(new LiteralSegment(value));
            }
        }
        return new CompiledPathTemplate(source, segments);
    }

    public Optional<Map<String, String>> match(String rawPath) {
        List<String> rawSegments = split(rawPath);
        if (rawSegments.size() != segments.size()) {
            return Optional.empty();
        }
        Map<String, String> variables = new LinkedHashMap<>();
        for (int index = 0; index < segments.size(); index++) {
            String decoded = decodeOnce(rawSegments.get(index));
            if (decoded.equals(".") || decoded.equals("..") || decoded.indexOf('\\') >= 0
                    || decoded.indexOf('/') >= 0 || decoded.codePoints().anyMatch(value -> value < 0x20 || value == 0x7f)) {
                throw new IllegalArgumentException("Unsafe path segment");
            }
            if (!segments.get(index).match(decoded, variables)) {
                return Optional.empty();
            }
        }
        return Optional.of(Collections.unmodifiableMap(variables));
    }

    public String source() {
        return source;
    }

    private static List<String> split(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with /");
        }
        if ("/".equals(path)) {
            return List.of();
        }
        return List.of(path.substring(1).split("/", -1));
    }

    private static String decodeOnce(String raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int index = 0; index < raw.length();) {
            char value = raw.charAt(index);
            if (value == '%') {
                if (index + 2 >= raw.length()) {
                    throw new IllegalArgumentException("Invalid percent encoding");
                }
                int high = Character.digit(raw.charAt(index + 1), 16);
                int low = Character.digit(raw.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid percent encoding");
                }
                bytes.write((high << 4) + low);
                index += 3;
            } else if (value <= 0x7f) {
                bytes.write(value);
                index++;
            } else {
                int codePoint = raw.codePointAt(index);
                byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(encoded);
                index += Character.charCount(codePoint);
            }
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()));
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid UTF-8 path segment", failure);
        }
    }

    private sealed interface Segment permits LiteralSegment, VariableSegment {
        boolean match(String value, Map<String, String> variables);
    }

    private record LiteralSegment(String value) implements Segment {
        @Override
        public boolean match(String candidate, Map<String, String> variables) {
            return value.equals(candidate);
        }
    }

    private record VariableSegment(String name) implements Segment {
        @Override
        public boolean match(String value, Map<String, String> variables) {
            if (value.isEmpty()) {
                return false;
            }
            variables.put(name, value);
            return true;
        }
    }
}
