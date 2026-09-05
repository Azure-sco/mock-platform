package com.xuntian.mock.control.flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.control.api.ApiRecord;
import com.xuntian.mock.control.api.ApiService;
import com.xuntian.mock.control.contract.ContractVersionMapper;
import com.xuntian.mock.control.contract.ContractVersionRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public final class FlowDefinitionValidator {

    private static final int MAX_PARTICIPANTS = 50;
    private static final int MAX_VARIABLES = 50;
    private static final int MAX_TRANSITIONS = 100;
    private static final long MAX_TTL_SECONDS = 30L * 24 * 60 * 60;
    private static final Set<String> ROLES = Set.of("CREATE", "QUERY", "COMMAND");
    private static final Set<String> TRIGGERS = Set.of("QUERY_COUNT", "TIME", "REQUEST_FIELD", "MANUAL");
    private static final Set<String> VARIABLE_TYPES = Set.of("STRING", "NUMBER", "BOOLEAN");
    private static final Set<String> ASSIGNMENTS = Set.of(
            "SET_LITERAL", "SET_FROM_REQUEST_FIELD", "INCREMENT_NUMBER", "CLEAR");
    private static final Set<String> SOURCES = Set.of("JSON_BODY", "QUERY", "HEADER");
    private static final Set<String> NORMALIZERS = Set.of("NONE", "TRIM", "UPPERCASE", "LOWERCASE");
    private static final Set<String> OPERATORS = Set.of("EQ", "NE", "CONTAINS", "PREFIX", "EXISTS");
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token");

    private final ObjectMapper objectMapper;
    private final ApiService apiService;
    private final ContractVersionMapper contractMapper;

    public FlowDefinitionValidator(
            ObjectMapper objectMapper,
            ApiService apiService,
            ContractVersionMapper contractMapper) {
        this.objectMapper = objectMapper;
        this.apiService = apiService;
        this.contractMapper = contractMapper;
    }

    public ValidationOutcome validate(FlowDefinitionRecord definition, FlowDefinitionVersionRecord version) {
        List<ValidationIssue> errors = new ArrayList<>();
        List<ValidationIssue> warnings = new ArrayList<>();
        JsonNode participants = parse(version.participantApisJson(), "participantApis", errors);
        JsonNode variables = parse(version.variablesJson(), "variables", errors);
        JsonNode transitions = parse(version.transitionsJson(), "transitions", errors);
        if (!"ENABLED".equals(definition.status())) {
            error(errors, "FLOW_DISABLED", "/", "Flow Definition must be ENABLED");
        }
        if (blank(version.initialState()) || version.initialState().length() > 64) {
            error(errors, "INITIAL_STATE_INVALID", "/initialState", "initialState is required and limited to 64 characters");
        }
        if (version.ttlSeconds() < 60 || version.ttlSeconds() > MAX_TTL_SECONDS) {
            error(errors, "TTL_INVALID", "/ttlSeconds", "ttlSeconds must be from 60 to 2592000");
        }

        ArrayNode normalizedParticipants = validateParticipants(definition, participants, errors);
        VariableValidation variableValidation = validateVariables(variables, errors);
        ArrayNode normalizedTransitions = validateTransitions(
                transitions, version.initialState(), variableValidation.types(), errors, warnings);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("compilerVersion", "flow-v1");
        compiled.put("flowDefinitionId", definition.id());
        compiled.put("flowDefinitionVersionId", version.id());
        compiled.put("flowCode", definition.flowCode());
        compiled.put("version", version.versionNo());
        compiled.put("checksum", version.checksum());
        compiled.put("initialState", version.initialState());
        compiled.put("ttlSeconds", version.ttlSeconds());
        compiled.set("participantApis", normalizedParticipants);
        compiled.set("variables", variableValidation.normalized());
        compiled.set("transitions", normalizedTransitions);
        return new ValidationOutcome(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings), compiled);
    }

    private ArrayNode validateParticipants(
            FlowDefinitionRecord definition,
            JsonNode source,
            List<ValidationIssue> errors) {
        ArrayNode normalized = objectMapper.createArrayNode();
        if (!source.isArray() || source.isEmpty() || source.size() > MAX_PARTICIPANTS) {
            error(errors, "PARTICIPANTS_INVALID", "/participantApis", "participantApis must contain 1 to 50 items");
            return normalized;
        }
        Set<String> apiCodes = new HashSet<>();
        int createCount = 0;
        for (int index = 0; index < source.size(); index++) {
            JsonNode item = source.get(index);
            String path = "/participantApis/" + index;
            if (!item.isObject()) {
                error(errors, "PARTICIPANT_INVALID", path, "Participant must be an object");
                continue;
            }
            String apiCode = text(item, "apiCode");
            String role = text(item, "role").toUpperCase(Locale.ROOT);
            long contractVersionId = item.path("contractVersionId").asLong(0);
            boolean createIfAbsent = item.path("createIfAbsent").asBoolean(false);
            if (blank(apiCode) || !apiCodes.add(apiCode)) {
                error(errors, "PARTICIPANT_API_DUPLICATE", path + "/apiCode", "apiCode must be unique and non-empty");
            }
            if (!ROLES.contains(role)) {
                error(errors, "PARTICIPANT_ROLE_INVALID", path + "/role", "role must be CREATE, QUERY or COMMAND");
            }
            if ("CREATE".equals(role)) {
                createCount++;
                if (!createIfAbsent) {
                    error(errors, "CREATE_FLAG_REQUIRED", path + "/createIfAbsent", "CREATE must set createIfAbsent=true");
                }
            } else if (createIfAbsent) {
                error(errors, "CREATE_FLAG_FORBIDDEN", path + "/createIfAbsent", "Only CREATE may create a Flow");
            }
            ContractVersionRecord contract = contractMapper.selectById(contractVersionId);
            ApiRecord api = null;
            if (contract == null || !"PUBLISHED".equals(contract.status())) {
                error(errors, "CONTRACT_NOT_PUBLISHED", path + "/contractVersionId", "Participant Contract Version must be PUBLISHED");
            } else {
                api = apiService.require(contract.apiId());
                if (!apiCode.equals(api.apiCode()) || api.providerId() != definition.providerId()) {
                    error(errors, "PARTICIPANT_CONTRACT_MISMATCH", path, "Participant API/Contract must belong to the Flow Provider");
                }
            }
            JsonNode extractor = item.get("businessKeyExtractor");
            validateExtractor(extractor, path + "/businessKeyExtractor", errors);
            ObjectNode target = normalized.addObject();
            target.put("apiCode", apiCode);
            target.put("contractVersionId", contractVersionId);
            target.put("role", role);
            target.put("createIfAbsent", createIfAbsent);
            target.set("businessKeyExtractor", extractor == null ? objectMapper.nullNode() : extractor.deepCopy());
        }
        if (createCount != 1) {
            error(errors, "CREATE_PARTICIPANT_COUNT", "/participantApis", "Exactly one CREATE Participant is required");
        }
        return normalized;
    }

    private void validateExtractor(JsonNode extractor, String path, List<ValidationIssue> errors) {
        if (extractor == null || !extractor.isObject()) {
            error(errors, "EXTRACTOR_REQUIRED", path, "A versioned Business Key Extractor is required");
            return;
        }
        String source = text(extractor, "source").toUpperCase(Locale.ROOT);
        String key = text(extractor, "path");
        String normalize = text(extractor, "normalize").toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(source)) error(errors, "EXTRACTOR_SOURCE", path + "/source", "Unsupported extractor source");
        if (blank(key) || key.length() > 512) error(errors, "EXTRACTOR_PATH", path + "/path", "Extractor path is invalid");
        if ("JSON_BODY".equals(source) && !key.startsWith("$.")) {
            error(errors, "EXTRACTOR_JSON_PATH", path + "/path", "JSON_BODY extractor path must start with $.");
        }
        if ("HEADER".equals(source) && SENSITIVE_HEADERS.contains(key.toLowerCase(Locale.ROOT))) {
            error(errors, "EXTRACTOR_SENSITIVE_HEADER", path + "/path", "Sensitive headers cannot be business keys");
        }
        if (!NORMALIZERS.contains(normalize)) {
            error(errors, "EXTRACTOR_NORMALIZER", path + "/normalize", "Unsupported business key normalizer");
        }
        if (!extractor.path("required").asBoolean(false)) {
            error(errors, "EXTRACTOR_REQUIRED_FLAG", path + "/required", "Flow business key must be required");
        }
    }

    private VariableValidation validateVariables(JsonNode source, List<ValidationIssue> errors) {
        ArrayNode normalized = objectMapper.createArrayNode();
        Map<String, String> types = new LinkedHashMap<>();
        if (!source.isArray() || source.size() > MAX_VARIABLES) {
            error(errors, "VARIABLES_INVALID", "/variables", "variables must be an array with at most 50 items");
            return new VariableValidation(normalized, Map.of());
        }
        for (int index = 0; index < source.size(); index++) {
            JsonNode item = source.get(index);
            String path = "/variables/" + index;
            if (!item.isObject()) {
                error(errors, "VARIABLE_INVALID", path, "Variable must be an object");
                continue;
            }
            String name = text(item, "name");
            String type = text(item, "type").toUpperCase(Locale.ROOT);
            if (blank(name) || name.length() > 64 || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || types.putIfAbsent(name, type) != null) {
                error(errors, "VARIABLE_NAME", path + "/name", "Variable name must be unique and identifier-like");
            }
            if (!VARIABLE_TYPES.contains(type)) error(errors, "VARIABLE_TYPE", path + "/type", "Unsupported variable type");
            int maxLength = item.path("maxLength").asInt(0);
            if ("STRING".equals(type) && (maxLength < 1 || maxLength > 4096)) {
                error(errors, "VARIABLE_MAX_LENGTH", path + "/maxLength", "STRING maxLength must be 1 to 4096");
            }
            JsonNode initial = item.get("initialValue");
            if (initial != null && !initial.isNull()) {
                if (!initial.isObject()) {
                    error(errors, "VARIABLE_INITIAL", path + "/initialValue", "initialValue must be an object");
                } else {
                    String initialSource = text(initial, "source").toUpperCase(Locale.ROOT);
                    if (!Set.of("LITERAL", "REQUEST_FIELD").contains(initialSource)) {
                        error(errors, "VARIABLE_INITIAL_SOURCE", path + "/initialValue/source", "Unsupported initial source");
                    }
                    if ("REQUEST_FIELD".equals(initialSource)
                            && blank(text(initial, "path"))) {
                        error(errors, "VARIABLE_INITIAL_PATH", path + "/initialValue/path", "REQUEST_FIELD path is required");
                    }
                }
            }
            normalized.add(item.deepCopy());
        }
        return new VariableValidation(normalized, Map.copyOf(types));
    }

    private ArrayNode validateTransitions(
            JsonNode source,
            String initialState,
            Map<String, String> variableTypes,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings) {
        ArrayNode normalized = objectMapper.createArrayNode();
        if (!source.isArray() || source.size() > MAX_TRANSITIONS) {
            error(errors, "TRANSITIONS_INVALID", "/transitions", "transitions must be an array with at most 100 items");
            return normalized;
        }
        Set<String> ids = new HashSet<>();
        Map<String, Set<String>> graph = new HashMap<>();
        Set<String> staticKeys = new HashSet<>();
        for (int index = 0; index < source.size(); index++) {
            JsonNode item = source.get(index);
            String path = "/transitions/" + index;
            if (!item.isObject()) {
                error(errors, "TRANSITION_INVALID", path, "Transition must be an object");
                continue;
            }
            String id = text(item, "transitionId");
            String from = text(item, "from");
            String to = text(item, "to");
            if (blank(id) || id.length() > 128 || !ids.add(id)) error(errors, "TRANSITION_ID", path + "/transitionId", "transitionId must be unique");
            if (blank(from) || blank(to) || from.length() > 64 || to.length() > 64 || from.equals(to)) {
                error(errors, "TRANSITION_STATE", path, "from/to must be distinct valid states");
            }
            graph.computeIfAbsent(from, ignored -> new HashSet<>()).add(to);
            JsonNode trigger = item.get("trigger");
            String type = trigger != null ? text(trigger, "type").toUpperCase(Locale.ROOT) : "";
            if (trigger == null || !trigger.isObject() || !TRIGGERS.contains(type)) {
                error(errors, "TRIGGER_TYPE", path + "/trigger/type", "Unsupported transition trigger");
            } else {
                validateTrigger(trigger, type, path + "/trigger", errors);
            }
            int priority = item.path("priority").asInt(Integer.MIN_VALUE);
            if (priority == Integer.MIN_VALUE) error(errors, "TRANSITION_PRIORITY", path + "/priority", "Integer priority is required");
            String conflictKey = from + '\u0000' + type + '\u0000' + priority;
            if (!staticKeys.add(conflictKey)) {
                error(errors, "TRANSITION_CONFLICT", path, "Same state/event/priority is statically ambiguous");
            }
            JsonNode assignments = item.path("assignments");
            if (!assignments.isArray()) {
                error(errors, "ASSIGNMENTS_INVALID", path + "/assignments", "assignments must be an array");
            } else {
                for (int assignmentIndex = 0; assignmentIndex < assignments.size(); assignmentIndex++) {
                    validateAssignment(assignments.get(assignmentIndex), variableTypes, type,
                            path + "/assignments/" + assignmentIndex, errors);
                }
            }
            normalized.add(item.deepCopy());
        }
        if (hasCycle(initialState, graph, new HashSet<>(), new HashSet<>())) {
            error(errors, "FLOW_CYCLE", "/transitions", "MVP Flow graph must be acyclic");
        }
        Set<String> reachable = new HashSet<>();
        visit(initialState, graph, reachable);
        graph.keySet().stream().filter(state -> !reachable.contains(state)).sorted()
                .forEach(state -> warnings.add(new ValidationIssue(
                        "UNREACHABLE_STATE", "/transitions", "State is unreachable from initialState: " + state)));
        return normalized;
    }

    private void validateTrigger(JsonNode trigger, String type, String path, List<ValidationIssue> errors) {
        if ("QUERY_COUNT".equals(type) && trigger.path("threshold").asLong(0) < 1) {
            error(errors, "QUERY_COUNT_THRESHOLD", path + "/threshold", "QUERY_COUNT threshold must be positive");
        }
        if ("TIME".equals(type) && trigger.path("delaySeconds").asLong(0) < 1) {
            error(errors, "TIME_DELAY", path + "/delaySeconds", "TIME delaySeconds must be positive");
        }
        if ("REQUEST_FIELD".equals(type)) {
            String source = text(trigger, "source").toUpperCase(Locale.ROOT);
            String key = text(trigger, "path");
            String operator = text(trigger, "operator").toUpperCase(Locale.ROOT);
            if (!SOURCES.contains(source)) error(errors, "TRIGGER_SOURCE", path + "/source", "Unsupported request source");
            if (blank(key)) error(errors, "TRIGGER_PATH", path + "/path", "Request field path is required");
            if ("HEADER".equals(source) && SENSITIVE_HEADERS.contains(key.toLowerCase(Locale.ROOT))) {
                error(errors, "TRIGGER_SENSITIVE_HEADER", path + "/path", "Sensitive headers cannot trigger a Flow");
            }
            if (!OPERATORS.contains(operator)) error(errors, "TRIGGER_OPERATOR", path + "/operator", "Unsupported request operator");
        }
    }

    private void validateAssignment(
            JsonNode assignment,
            Map<String, String> variableTypes,
            String triggerType,
            String path,
            List<ValidationIssue> errors) {
        if (!assignment.isObject()) {
            error(errors, "ASSIGNMENT_INVALID", path, "Assignment must be an object");
            return;
        }
        String type = text(assignment, "type").toUpperCase(Locale.ROOT);
        String variable = text(assignment, "variable");
        if (!ASSIGNMENTS.contains(type)) error(errors, "ASSIGNMENT_TYPE", path + "/type", "Unsupported assignment type");
        if (!variableTypes.containsKey(variable)) error(errors, "ASSIGNMENT_VARIABLE", path + "/variable", "Assignment variable is undeclared");
        if ("INCREMENT_NUMBER".equals(type) && !"NUMBER".equals(variableTypes.get(variable))) {
            error(errors, "ASSIGNMENT_NUMBER", path, "INCREMENT_NUMBER requires a NUMBER variable");
        }
        if ("SET_FROM_REQUEST_FIELD".equals(type) && blank(text(assignment, "path"))) {
            error(errors, "ASSIGNMENT_PATH", path + "/path", "Request field path is required");
        }
        if ("SET_FROM_REQUEST_FIELD".equals(type)
                && Set.of("TIME", "MANUAL").contains(triggerType)) {
            error(errors, "ASSIGNMENT_REQUEST_CONTEXT", path,
                    "TIME and MANUAL transitions cannot read SDK request fields");
        }
    }

    private boolean hasCycle(
            String state,
            Map<String, Set<String>> graph,
            Set<String> visiting,
            Set<String> visited) {
        if (visiting.contains(state)) return true;
        if (!visited.add(state)) return false;
        visiting.add(state);
        for (String next : graph.getOrDefault(state, Set.of())) {
            if (hasCycle(next, graph, visiting, visited)) return true;
        }
        visiting.remove(state);
        return false;
    }

    private void visit(String state, Map<String, Set<String>> graph, Set<String> result) {
        if (!result.add(state)) return;
        graph.getOrDefault(state, Set.of()).forEach(next -> visit(next, graph, result));
    }

    private JsonNode parse(String json, String field, List<ValidationIssue> errors) {
        try {
            return objectMapper.readTree(json == null ? "null" : json);
        } catch (JsonProcessingException failure) {
            error(errors, "JSON_INVALID", "/" + field, field + " is not valid JSON");
            return objectMapper.nullNode();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void error(List<ValidationIssue> errors, String code, String path, String message) {
        errors.add(new ValidationIssue(code, path, message));
    }

    private record VariableValidation(ArrayNode normalized, Map<String, String> types) { }

    public record ValidationIssue(String code, String path, String message) { }

    public record ValidationOutcome(
            boolean valid,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings,
            ObjectNode compiled) { }
}
