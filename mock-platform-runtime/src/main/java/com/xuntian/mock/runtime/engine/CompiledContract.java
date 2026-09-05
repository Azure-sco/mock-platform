package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.io.IOException;

public final class CompiledContract {

    private final String method;
    private final CompiledPathTemplate path;
    private final Set<String> contentTypes;
    private final CompiledJsonSchema requestSchema;
    private final CompiledJsonSchema responseSchema;
    private final BusinessKeyExtractor businessKeyExtractor;

    public CompiledContract(
            String method,
            CompiledPathTemplate path,
            Set<String> contentTypes,
            CompiledJsonSchema requestSchema,
            CompiledJsonSchema responseSchema,
            BusinessKeyExtractor businessKeyExtractor) {
        this.method = method.toUpperCase(Locale.ROOT);
        this.path = path;
        this.contentTypes = contentTypes.stream()
                .map(CompiledContract::normalizeContentType)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.requestSchema = requestSchema;
        this.responseSchema = responseSchema;
        this.businessKeyExtractor = businessKeyExtractor;
    }

    public ContractMatch validate(RuntimeRequest request, ObjectMapper mapper) {
        if (!method.equalsIgnoreCase(request.method())) {
            throw mismatch("HTTP method does not match published contract");
        }
        try {
            if (path.match(request.rawPath()).isEmpty()) {
                throw mismatch("Path does not match published contract");
            }
        } catch (IllegalArgumentException failure) {
            throw new PlatformException(ErrorCode.MOCK_CONTRACT_MISMATCH, "Path is invalid", failure);
        }
        String normalizedType = normalizeContentType(request.contentType());
        if (!contentTypes.isEmpty() && !contentTypes.contains(normalizedType)) {
            throw mismatch("Content-Type does not match published contract");
        }
        JsonNode body = null;
        if (contentTypes.contains("application/json") || "application/json".equals(normalizedType)) {
            try {
                body = request.bodyLength() == 0 ? mapper.nullNode() : mapper.readTree(request.body());
                requestSchema.validate(body);
            } catch (IOException | IllegalArgumentException failure) {
                throw new PlatformException(
                        ErrorCode.MOCK_CONTRACT_MISMATCH,
                        "JSON request body does not match published contract",
                        failure);
            }
        }
        String businessNo = businessKeyExtractor == null
                ? null
                : businessKeyExtractor.extract(request, body).orElse(null);
        return new ContractMatch(body, businessNo);
    }

    public void validateResponse(JsonNode response) {
        try {
            responseSchema.validate(response);
        } catch (IllegalArgumentException failure) {
            throw new PlatformException(
                    ErrorCode.MOCK_TEMPLATE_RENDER_FAILED,
                    "Rendered response violates published contract",
                    failure);
        }
    }

    public CompiledJsonSchema responseSchema() {
        return responseSchema;
    }

    private static String normalizeContentType(String value) {
        if (value == null) {
            return "";
        }
        int parameter = value.indexOf(';');
        return (parameter < 0 ? value : value.substring(0, parameter)).trim().toLowerCase(Locale.ROOT);
    }

    private PlatformException mismatch(String message) {
        return new PlatformException(ErrorCode.MOCK_CONTRACT_MISMATCH, message);
    }

    public record ContractMatch(JsonNode body, String businessNo) { }

    public static final class BusinessKeyExtractor {
        private final Source source;
        private final String path;
        private final boolean required;
        private final boolean trim;
        private final CompiledJsonPath jsonPath;

        public BusinessKeyExtractor(String source, String path, boolean required, String normalize) {
            this.source = Source.valueOf(source.toUpperCase(Locale.ROOT));
            this.path = path;
            this.required = required;
            this.trim = "TRIM".equalsIgnoreCase(normalize);
            this.jsonPath = this.source == Source.JSON_BODY ? CompiledJsonPath.compile(path) : null;
        }

        Optional<String> extract(RuntimeRequest request, JsonNode body) {
            Optional<String> value = switch (source) {
                case HEADER -> request.firstHeader(path);
                case QUERY -> request.firstQuery(path);
                case JSON_BODY -> jsonPath.read(body).filter(JsonNode::isValueNode).map(JsonNode::asText);
            };
            value = value.map(candidate -> trim ? candidate.trim() : candidate).filter(candidate -> !candidate.isEmpty());
            if (required && value.isEmpty()) {
                throw new PlatformException(
                        ErrorCode.MOCK_BUSINESS_KEY_MISSING,
                        "Published contract business key is missing");
            }
            return value;
        }

        private enum Source { HEADER, QUERY, JSON_BODY }
    }
}
