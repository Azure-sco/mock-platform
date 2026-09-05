package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CompiledTemplate {

    public static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_TOKENS = 500;

    private final List<Part> parts;
    private final Map<String, String> defaults;
    private final ObjectMapper mapper;

    private CompiledTemplate(List<Part> parts, Map<String, String> defaults, ObjectMapper mapper) {
        this.parts = List.copyOf(parts);
        this.defaults = Map.copyOf(defaults);
        this.mapper = mapper;
    }

    public static CompiledTemplate compile(String source, Map<String, String> defaults, ObjectMapper mapper) {
        if (source == null || source.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Response template is missing or exceeds 1MB");
        }
        List<Part> parts = new ArrayList<>();
        StringBuilder validation = new StringBuilder(source.length());
        int cursor = 0;
        int tokens = 0;
        boolean inString = false;
        boolean escaped = false;
        while (cursor < source.length()) {
            int tokenStart = source.indexOf("${", cursor);
            int literalEnd = tokenStart < 0 ? source.length() : tokenStart;
            String literal = source.substring(cursor, literalEnd);
            parts.add(new LiteralPart(literal));
            validation.append(literal);
            for (int index = 0; index < literal.length(); index++) {
                char current = literal.charAt(index);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\' && inString) {
                    escaped = true;
                } else if (current == '"') {
                    inString = !inString;
                }
            }
            if (tokenStart < 0) {
                break;
            }
            int tokenEnd = source.indexOf('}', tokenStart + 2);
            if (tokenEnd < 0) {
                throw new IllegalArgumentException("Unclosed template token");
            }
            String expression = source.substring(tokenStart + 2, tokenEnd);
            boolean typed = expression.startsWith("json:");
            String name = typed ? expression.substring("json:".length()) : expression;
            validateToken(name);
            if (typed == inString) {
                throw new IllegalArgumentException(typed
                        ? "json: token must be a complete JSON value"
                        : "String token must appear inside a JSON string");
            }
            parts.add(TokenPart.compile(name, typed));
            validation.append(typed ? "null" : "value");
            tokens++;
            if (tokens > MAX_TOKENS) {
                throw new IllegalArgumentException("Response template exceeds 500 tokens");
            }
            cursor = tokenEnd + 1;
        }
        try {
            mapper.readTree(validation.toString());
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Response template is not valid JSON", failure);
        }
        return new CompiledTemplate(parts, defaults == null ? Map.of() : defaults, mapper);
    }

    public RenderedTemplate render(TemplateContext context) {
        StringBuilder output = new StringBuilder();
        for (Part part : parts) {
            part.append(output, context, defaults, mapper);
            if (output.length() > MAX_BODY_BYTES) {
                throw renderFailure("Rendered response exceeds 1MB", null);
            }
        }
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BODY_BYTES) {
            throw renderFailure("Rendered response exceeds 1MB", null);
        }
        try {
            return new RenderedTemplate(bytes, mapper.readTree(bytes));
        } catch (IOException failure) {
            throw renderFailure("Rendered response is not valid JSON", failure);
        }
    }

    private static void validateToken(String token) {
        boolean valid = token.equals("flow.businessNo")
                || token.equals("state.current")
                || token.equals("traceId")
                || token.equals("mockRequestId")
                || token.equals("now.iso")
                || token.equals("now.epochMillis")
                || token.equals("uuid")
                || token.startsWith("request.body.$.")
                || token.startsWith("request.header.")
                || token.startsWith("request.query.")
                || token.startsWith("flow.variable.");
        if (!valid) {
            throw new IllegalArgumentException("Unsupported template token: " + token);
        }
        if (token.startsWith("request.body.")) {
            CompiledJsonPath.compile(token.substring("request.body.".length()));
        }
        if ((token.startsWith("request.header.") && token.length() == "request.header.".length())
                || (token.startsWith("request.query.") && token.length() == "request.query.".length())
                || (token.startsWith("flow.variable.") && token.length() == "flow.variable.".length())) {
            throw new IllegalArgumentException("Template token path is empty: " + token);
        }
        if (token.startsWith("request.header.")
                && RuntimeRequest.isSensitiveHeader(token.substring("request.header.".length()))) {
            throw new IllegalArgumentException("Sensitive headers cannot be used by templates");
        }
    }

    private static PlatformException missing(String token) {
        return new PlatformException(
                ErrorCode.MOCK_TEMPLATE_VARIABLE_MISSING,
                "Template variable is missing: " + token);
    }

    private static PlatformException renderFailure(String message, Throwable cause) {
        return cause == null
                ? new PlatformException(ErrorCode.MOCK_TEMPLATE_RENDER_FAILED, message)
                : new PlatformException(ErrorCode.MOCK_TEMPLATE_RENDER_FAILED, message, cause);
    }

    private sealed interface Part permits LiteralPart, TokenPart {
        void append(StringBuilder target, TemplateContext context, Map<String, String> defaults, ObjectMapper mapper);
    }

    private record LiteralPart(String value) implements Part {
        @Override
        public void append(StringBuilder target, TemplateContext context, Map<String, String> defaults, ObjectMapper mapper) {
            target.append(value);
        }
    }

    private record TokenPart(String token, boolean typed, CompiledJsonPath jsonPath) implements Part {

        static TokenPart compile(String token, boolean typed) {
            CompiledJsonPath path = token.startsWith("request.body.")
                    ? CompiledJsonPath.compile(token.substring("request.body.".length()))
                    : null;
            return new TokenPart(token, typed, path);
        }

        @Override
        public void append(StringBuilder target, TemplateContext context, Map<String, String> defaults, ObjectMapper mapper) {
            Object value = resolve(context).orElseGet(() -> defaults.get(token));
            if (value == null) {
                throw missing(token);
            }
            try {
                if (typed) {
                    target.append(mapper.writeValueAsString(value));
                } else {
                    String encoded = mapper.writeValueAsString(asString(value));
                    target.append(encoded, 1, encoded.length() - 1);
                }
            } catch (JsonProcessingException failure) {
                throw renderFailure("Template variable cannot be rendered: " + token, failure);
            }
        }

        private Optional<Object> resolve(TemplateContext context) {
            if (token.equals("flow.businessNo")) return Optional.ofNullable(context.businessNo());
            if (token.equals("state.current")) return Optional.ofNullable(context.state());
            if (token.equals("traceId")) return Optional.of(context.request().traceId());
            if (token.equals("mockRequestId")) return Optional.of(context.request().mockRequestId());
            if (token.equals("now.iso")) return Optional.of(DateTimeFormatter.ISO_INSTANT.format(context.now()));
            if (token.equals("now.epochMillis")) return Optional.of(context.now().toEpochMilli());
            if (token.equals("uuid")) return Optional.of(context.uuid().toString());
            if (token.startsWith("request.header.")) {
                return context.request().firstHeader(token.substring("request.header.".length())).map(value -> value);
            }
            if (token.startsWith("request.query.")) {
                return context.request().firstQuery(token.substring("request.query.".length())).map(value -> value);
            }
            if (token.startsWith("request.body.")) {
                return jsonPath.read(context.body()).map(value -> (Object) value);
            }
            if (token.startsWith("flow.variable.")) {
                return Optional.ofNullable(context.flowVariables().get(token.substring("flow.variable.".length())));
            }
            return Optional.empty();
        }

        private String asString(Object value) {
            return value instanceof JsonNode node && node.isValueNode() ? node.asText() : String.valueOf(value);
        }
    }

    public record RenderedTemplate(byte[] bytes, JsonNode json) {
        public RenderedTemplate {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
