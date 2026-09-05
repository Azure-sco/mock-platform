package com.xuntian.mock.control.contract;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public final class ContractImportService {

    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final int MAX_CODE_POINTS = 5_000_000;
    static final int MAX_DEPTH = 64;
    static final int MAX_NODES = 100_000;
    static final int MAX_REF_DEPTH = 32;
    static final int MAX_REF_RESOLUTIONS = 1_000;
    private static final long PARSE_TIMEOUT_SECONDS = 3;
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "put", "post", "delete", "patch", "head", "options", "trace");

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final ThreadPoolExecutor parserExecutor;

    public ContractImportService() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_DEPTH)
                .maxStringLength(MAX_CODE_POINTS)
                .build();
        this.jsonMapper = new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(constraints)
                .build());

        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setAllowRecursiveKeys(false);
        loaderOptions.setMaxAliasesForCollections(0);
        loaderOptions.setNestingDepthLimit(MAX_DEPTH);
        loaderOptions.setCodePointLimit(MAX_CODE_POINTS);
        this.yamlMapper = new ObjectMapper(YAMLFactory.builder()
                .streamReadConstraints(constraints)
                .loaderOptions(loaderOptions)
                .build());

        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "contract-import-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.parserExecutor = new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(20),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public ParsedContract parse(byte[] content, String fileName, ImportOptions options) {
        requireUpload(content, fileName);
        ImportOptions normalized = options == null ? new ImportOptions(null, null, null) : options;
        Future<ParsedContract> future;
        try {
            future = parserExecutor.submit(() -> parseRestricted(content, fileName, normalized));
        } catch (RejectedExecutionException busy) {
            throw invalid("Contract import queue is full; retry later", busy);
        }
        try {
            return future.get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw invalid("Contract import exceeded the 3 second limit", timeout);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw invalid("Contract import was interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof PlatformException platformFailure) {
                throw platformFailure;
            }
            throw invalid("Contract file could not be parsed", failed.getCause());
        }
    }

    @PreDestroy
    void shutdown() {
        parserExecutor.shutdownNow();
    }

    private ParsedContract parseRestricted(byte[] content, String fileName, ImportOptions options) {
        String source = decodeUtf8(content);
        boolean yaml = isYaml(fileName);
        if (yaml && containsYamlAliasOrAnchor(source)) {
            throw invalid("YAML anchors and aliases are not allowed");
        }
        JsonNode document;
        try {
            document = (yaml ? yamlMapper : jsonMapper).readTree(source);
        } catch (JsonProcessingException malformed) {
            throw invalid("Contract file is not valid " + (yaml ? "YAML" : "JSON"), malformed);
        }
        if (document == null || !document.isObject()) {
            throw invalid("Contract file root must be an object");
        }
        validateComplexity(document);
        validateAllReferences(document, document, new ArrayDeque<>(), new int[]{0});

        ParsedContent parsed = isOpenApi(document)
                ? parseOpenApi(document, options)
                : parseJsonSchema(document, options);
        return new ParsedContract(
                parsed.requestSchema(),
                parsed.responseSchema(),
                parsed.examples(),
                parsed.sourceType(),
                Checksum.sha256Hex(content));
    }

    private ParsedContent parseOpenApi(JsonNode document, ImportOptions options) {
        String version = text(document.get("openapi"));
        if (version == null || !version.startsWith("3.0.")) {
            throw invalid("Only OpenAPI 3.0 documents are supported");
        }
        Operation operation = selectOperation(document, options.path(), options.method());
        int[] resolutions = {0};
        JsonNode resolvedOperation = resolve(
                document, operation.value(), new ArrayDeque<>(), resolutions, new int[]{0});
        JsonNode requestMedia = mediaType(resolvedOperation.path("requestBody").path("content"));
        JsonNode requestSchema = schemaOrEmpty(document, requestMedia.path("schema"), resolutions);

        JsonNode response = selectResponse(resolvedOperation.path("responses"));
        JsonNode responseMedia = mediaType(response.path("content"));
        JsonNode responseSchema = schemaOrEmpty(document, responseMedia.path("schema"), resolutions);

        ObjectNode examples = JsonNodeFactory.instance.objectNode();
        addExample(examples, "request", requestMedia);
        addExample(examples, "response", responseMedia);
        return new ParsedContent(
                requestSchema,
                responseSchema,
                examples.isEmpty() ? null : examples,
                "OPENAPI");
    }

    private ParsedContent parseJsonSchema(JsonNode document, ImportOptions options) {
        String target = options.target() == null
                ? "REQUEST"
                : options.target().trim().toUpperCase(Locale.ROOT);
        if (!"REQUEST".equals(target) && !"RESPONSE".equals(target)) {
            throw invalid("target must be REQUEST or RESPONSE for JSON Schema import");
        }
        JsonNode schema = resolve(document, document, new ArrayDeque<>(), new int[]{0}, new int[]{0});
        validateComplexity(schema);
        JsonNode empty = emptySchema();
        return "REQUEST".equals(target)
                ? new ParsedContent(schema, empty, null, "JSON_SCHEMA")
                : new ParsedContent(empty, schema, null, "JSON_SCHEMA");
    }

    private Operation selectOperation(JsonNode document, String requestedPath, String requestedMethod) {
        JsonNode paths = document.path("paths");
        if (!paths.isObject()) {
            throw invalid("OpenAPI paths must be an object");
        }
        if ((requestedPath == null) != (requestedMethod == null)) {
            throw invalid("path and method must be supplied together");
        }
        if (requestedPath != null) {
            String method = requestedMethod.trim().toLowerCase(Locale.ROOT);
            if (!HTTP_METHODS.contains(method)) {
                throw invalid("OpenAPI method is invalid");
            }
            JsonNode operation = paths.path(requestedPath).path(method);
            if (!operation.isObject()) {
                throw invalid("Selected OpenAPI operation was not found");
            }
            return new Operation(requestedPath, method, operation);
        }

        List<Operation> operations = new ArrayList<>();
        paths.fields().forEachRemaining(path -> {
            if (path.getValue().isObject()) {
                path.getValue().fields().forEachRemaining(method -> {
                    if (HTTP_METHODS.contains(method.getKey().toLowerCase(Locale.ROOT))
                            && method.getValue().isObject()) {
                        operations.add(new Operation(path.getKey(), method.getKey(), method.getValue()));
                    }
                });
            }
        });
        if (operations.size() != 1) {
            throw invalid("OpenAPI file contains multiple operations; path and method are required");
        }
        return operations.get(0);
    }

    private JsonNode selectResponse(JsonNode responses) {
        if (!responses.isObject()) {
            return JsonNodeFactory.instance.objectNode();
        }
        List<Map.Entry<String, JsonNode>> successful = new ArrayList<>();
        responses.fields().forEachRemaining(entry -> {
            if (entry.getKey().matches("2[0-9][0-9]")) successful.add(entry);
        });
        successful.sort(Comparator.comparing(Map.Entry::getKey));
        if (!successful.isEmpty()) return successful.get(0).getValue();
        return responses.path("default");
    }

    private JsonNode mediaType(JsonNode content) {
        if (!content.isObject()) return JsonNodeFactory.instance.objectNode();
        JsonNode json = content.get("application/json");
        if (json != null && json.isObject()) return json;
        Iterator<JsonNode> values = content.elements();
        return values.hasNext() ? values.next() : JsonNodeFactory.instance.objectNode();
    }

    private JsonNode schemaOrEmpty(JsonNode root, JsonNode schema, int[] resolutions) {
        if (schema == null || !schema.isObject()) return emptySchema();
        JsonNode resolved = resolve(root, schema, new ArrayDeque<>(), resolutions, new int[]{0});
        validateComplexity(resolved);
        return resolved;
    }

    private void addExample(ObjectNode examples, String name, JsonNode media) {
        JsonNode example = media.get("example");
        if (example != null) {
            examples.set(name, example.deepCopy());
            return;
        }
        JsonNode named = media.get("examples");
        if (named != null && named.isObject()) examples.set(name, named.deepCopy());
    }

    private void validateAllReferences(JsonNode root, JsonNode node, Deque<String> stack, int[] count) {
        if (node.isObject()) {
            JsonNode reference = node.get("$ref");
            if (reference != null) {
                String value = requireLocalReference(reference);
                if (stack.size() >= MAX_REF_DEPTH) throw invalid("Local $ref exceeds depth 32");
                if (++count[0] > MAX_REF_RESOLUTIONS) throw invalid("Local $ref exceeds 1000 resolutions");
                if (stack.contains(value)) throw invalid("Recursive local $ref is not supported");
                JsonNode target = target(root, value);
                stack.addLast(value);
                validateAllReferences(root, target, stack, count);
                stack.removeLast();
            }
            node.fields().forEachRemaining(field -> {
                if (!"$ref".equals(field.getKey())) validateAllReferences(root, field.getValue(), stack, count);
            });
        } else if (node.isArray()) {
            node.forEach(item -> validateAllReferences(root, item, stack, count));
        }
    }

    private JsonNode resolve(
            JsonNode root,
            JsonNode node,
            Deque<String> stack,
            int[] count,
            int[] outputNodes) {
        if (++outputNodes[0] > MAX_NODES) {
            throw invalid("Expanded contract exceeds 100000 nodes");
        }
        if (node.isObject()) {
            JsonNode reference = node.get("$ref");
            ObjectNode result;
            if (reference != null) {
                String value = requireLocalReference(reference);
                if (stack.size() >= MAX_REF_DEPTH) throw invalid("Local $ref exceeds depth 32");
                if (++count[0] > MAX_REF_RESOLUTIONS) throw invalid("Local $ref exceeds 1000 resolutions");
                if (stack.contains(value)) throw invalid("Recursive local $ref is not supported");
                stack.addLast(value);
                JsonNode resolved = resolve(root, target(root, value), stack, count, outputNodes);
                stack.removeLast();
                if (!resolved.isObject()) throw invalid("Local $ref with sibling fields must resolve to an object");
                result = ((ObjectNode) resolved).deepCopy();
            } else {
                result = JsonNodeFactory.instance.objectNode();
            }
            node.fields().forEachRemaining(field -> {
                if (!"$ref".equals(field.getKey())) {
                    result.set(field.getKey(), resolve(root, field.getValue(), stack, count, outputNodes));
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(resolve(root, item, stack, count, outputNodes)));
            return result;
        }
        return node.deepCopy();
    }

    private String requireLocalReference(JsonNode reference) {
        if (!reference.isTextual() || !reference.textValue().startsWith("#/")) {
            throw invalid("External $ref is not allowed");
        }
        return reference.textValue();
    }

    private JsonNode target(JsonNode root, String reference) {
        JsonNode target = root.at(reference.substring(1));
        if (target.isMissingNode()) throw invalid("Local $ref target was not found");
        return target;
    }

    private void validateComplexity(JsonNode root) {
        int[] nodes = {0};
        validateComplexity(root, 0, nodes);
    }

    private void validateComplexity(JsonNode node, int depth, int[] nodes) {
        if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) {
            throw invalid("Contract document exceeds depth or node limits");
        }
        node.forEach(child -> validateComplexity(child, depth + 1, nodes));
    }

    private void requireUpload(byte[] content, String fileName) {
        if (content == null || content.length == 0) throw invalid("Contract file is empty");
        if (content.length > MAX_FILE_BYTES) {
            throw new PlatformException(ErrorCode.PAYLOAD_TOO_LARGE, "Contract file exceeds 5 MB");
        }
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".zip") || normalized.endsWith(".gz") || normalized.endsWith(".gzip")
                || isZip(content) || isGzip(content)) {
            throw invalid("Compressed contract files are not allowed");
        }
        if (!(normalized.endsWith(".json") || normalized.endsWith(".yaml") || normalized.endsWith(".yml"))) {
            throw invalid("Contract file must use .json, .yaml, or .yml");
        }
    }

    private String decodeUtf8(byte[] content) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            if (decoded.codePointCount(0, decoded.length()) > MAX_CODE_POINTS) {
                throw invalid("Contract file exceeds 5000000 code points");
            }
            return decoded.startsWith("\ufeff") ? decoded.substring(1) : decoded;
        } catch (CharacterCodingException malformed) {
            throw invalid("Contract file must be UTF-8", malformed);
        }
    }

    private boolean containsYamlAliasOrAnchor(String value) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (doubleQuoted && escaped) {
                escaped = false;
                continue;
            }
            if (doubleQuoted && current == '\\') {
                escaped = true;
                continue;
            }
            if (!doubleQuoted && current == '\'') singleQuoted = !singleQuoted;
            else if (!singleQuoted && current == '"') doubleQuoted = !doubleQuoted;
            else if (!singleQuoted && !doubleQuoted && current == '#') {
                while (index < value.length() && value.charAt(index) != '\n') index++;
            } else if (!singleQuoted && !doubleQuoted && (current == '&' || current == '*')
                    && isYamlTokenBoundary(value, index - 1)
                    && index + 1 < value.length()
                    && isYamlAnchorName(value.charAt(index + 1))) {
                return true;
            }
        }
        return false;
    }

    private boolean isYamlTokenBoundary(String value, int index) {
        return index < 0 || Character.isWhitespace(value.charAt(index))
                || "[{,:".indexOf(value.charAt(index)) >= 0;
    }

    private boolean isYamlAnchorName(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-';
    }

    private boolean isOpenApi(JsonNode document) {
        return document.has("openapi");
    }

    private boolean isYaml(String fileName) {
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".yaml") || normalized.endsWith(".yml");
    }

    private boolean isZip(byte[] content) {
        return content.length >= 4 && content[0] == 'P' && content[1] == 'K'
                && ((content[2] == 3 && content[3] == 4)
                || (content[2] == 5 && content[3] == 6)
                || (content[2] == 7 && content[3] == 8));
    }

    private boolean isGzip(byte[] content) {
        return content.length >= 2 && (content[0] & 0xff) == 0x1f && (content[1] & 0xff) == 0x8b;
    }

    private JsonNode emptySchema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
    }

    private String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private PlatformException invalid(String message) {
        return new PlatformException(ErrorCode.INVALID_REQUEST, message);
    }

    private PlatformException invalid(String message, Throwable cause) {
        return new PlatformException(ErrorCode.INVALID_REQUEST, message, cause);
    }

    public record ImportOptions(String path, String method, String target) {
    }

    public record ParsedContract(
            JsonNode requestSchema,
            JsonNode responseSchema,
            JsonNode examples,
            String sourceType,
            String sourceFileHash) {
    }

    private record ParsedContent(
            JsonNode requestSchema,
            JsonNode responseSchema,
            JsonNode examples,
            String sourceType) {
    }

    private record Operation(String path, String method, JsonNode value) {
    }
}
