package com.xuntian.mock.runtime.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.engine.CompiledJsonPath;
import com.xuntian.mock.runtime.engine.RuntimeRequest;
import com.xuntian.mock.runtime.snapshot.FixtureDefinition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Fully compiled, deeply immutable Flow artifact. */
public final class CompiledFlowDefinition {

    public static final long MIN_TTL_SECONDS = 3_600;
    public static final long MAX_TTL_SECONDS = 7 * 24 * 3_600;
    private static final int MAX_VARIABLES = 50;
    private static final int MAX_VARIABLES_JSON_BYTES = 8 * 1024;
    private static final int MAX_BUSINESS_KEY_BYTES = 512;
    private static final int MAX_REGEX_LENGTH = 500;
    private static final Comparator<Transition> TRANSITION_ORDER = Comparator
            .comparingInt(Transition::priority).reversed()
            .thenComparing(Transition::transitionId);

    private final String flowDefinitionId;
    private final String flowDefinitionVersionId;
    private final String provider;
    private final String flowCode;
    private final long version;
    private final String checksum;
    private final String initialState;
    private final Duration ttl;
    private final Map<String, Participant> participants;
    private final Map<String, Variable> variables;
    private final List<Transition> transitions;

    private CompiledFlowDefinition(
            String flowDefinitionId,
            String flowDefinitionVersionId,
            String provider,
            String flowCode,
            long version,
            String checksum,
            String initialState,
            Duration ttl,
            Map<String, Participant> participants,
            Map<String, Variable> variables,
            List<Transition> transitions) {
        this.flowDefinitionId = flowDefinitionId;
        this.flowDefinitionVersionId = flowDefinitionVersionId;
        this.provider = provider;
        this.flowCode = flowCode;
        this.version = version;
        this.checksum = checksum;
        this.initialState = initialState;
        this.ttl = ttl;
        this.participants = Map.copyOf(new LinkedHashMap<>(participants));
        this.variables = Map.copyOf(new LinkedHashMap<>(variables));
        this.transitions = transitions.stream().sorted(TRANSITION_ORDER).toList();
    }

    public static CompiledFlowDefinition compile(
            FixtureDefinition.FlowDefinition source,
            ObjectMapper mapper) {
        Objects.requireNonNull(source, "Flow Definition is required");
        String definitionId = safeId(source.flowDefinitionId(), "flowDefinitionId", 64);
        String versionId = safeId(source.flowDefinitionVersionId(), "flowDefinitionVersionId", 64);
        String provider = safeId(source.provider(), "flow.provider", 64);
        String flowCode = safeId(source.flowCode(), "flowCode", 64);
        String initialState = state(source.initialState(), "initialState");
        if (source.version() <= 0) {
            throw new IllegalArgumentException("Flow version must be positive");
        }
        if (source.checksum() == null || !source.checksum().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Flow checksum must be lowercase SHA-256");
        }
        if (source.ttlSeconds() < MIN_TTL_SECONDS || source.ttlSeconds() > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("Flow TTL must be between 1 hour and 7 days");
        }

        Map<String, Participant> participants = compileParticipants(source.participantApis());
        Map<String, Variable> variables = compileVariables(source.variables());
        List<Transition> transitions = compileTransitions(source.transitions(), variables);
        validateGraph(initialState, transitions);
        validateStaticConflicts(transitions);
        return new CompiledFlowDefinition(
                definitionId, versionId, provider, flowCode, source.version(), source.checksum(),
                initialState, Duration.ofSeconds(source.ttlSeconds()), participants, variables, transitions);
    }

    private static Map<String, Participant> compileParticipants(
            List<FixtureDefinition.FlowParticipantDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("Flow must contain Participant APIs");
        }
        Map<String, Participant> result = new LinkedHashMap<>();
        for (FixtureDefinition.FlowParticipantDefinition source : definitions) {
            String api = safeId(source.apiCode(), "participant.apiCode", 64);
            if (source.contractVersionId() <= 0) {
                throw new IllegalArgumentException("Participant contractVersionId must be positive");
            }
            Role role = enumValue(Role.class, source.role(), "participant.role");
            if (source.createIfAbsent() && role != Role.CREATE) {
                throw new IllegalArgumentException("Only CREATE Participant may create a missing Flow");
            }
            if (role == Role.CREATE && !source.createIfAbsent()) {
                throw new IllegalArgumentException("CREATE Participant must set createIfAbsent=true");
            }
            Participant participant = new Participant(
                    api, source.contractVersionId(), role, source.createIfAbsent(),
                    BusinessKeyExtractor.compile(source.businessKeyExtractor()));
            if (result.put(api, participant) != null) {
                throw new IllegalArgumentException("Flow Participant API must be unique: " + api);
            }
        }
        return result;
    }

    private static Map<String, Variable> compileVariables(
            List<FixtureDefinition.FlowVariableDefinition> definitions) {
        List<FixtureDefinition.FlowVariableDefinition> safe = definitions == null ? List.of() : definitions;
        if (safe.size() > MAX_VARIABLES) {
            throw new IllegalArgumentException("Flow cannot declare more than 50 variables");
        }
        Map<String, Variable> result = new LinkedHashMap<>();
        for (FixtureDefinition.FlowVariableDefinition source : safe) {
            String name = safeId(source.name(), "variable.name", 64);
            ValueType type = enumValue(ValueType.class, source.type(), "variable.type");
            int maxLength = source.maxLength() == null ? 0 : source.maxLength();
            if (type == ValueType.STRING && (maxLength < 1 || maxLength > 2_000)) {
                throw new IllegalArgumentException("STRING variable maxLength must be between 1 and 2000");
            }
            if (type != ValueType.STRING && source.maxLength() != null) {
                throw new IllegalArgumentException("maxLength is only valid for STRING variables");
            }
            InitialValue initial = InitialValue.compile(source.initialValue());
            Variable variable = new Variable(name, type, source.required(), maxLength, initial);
            if (result.put(name, variable) != null) {
                throw new IllegalArgumentException("Flow variable must be unique: " + name);
            }
        }
        return result;
    }

    private static List<Transition> compileTransitions(
            List<FixtureDefinition.FlowTransitionDefinition> definitions,
            Map<String, Variable> variables) {
        List<FixtureDefinition.FlowTransitionDefinition> safe = definitions == null ? List.of() : definitions;
        List<Transition> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (FixtureDefinition.FlowTransitionDefinition source : safe) {
            String id = safeId(source.transitionId(), "transitionId", 128);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Flow transitionId must be unique: " + id);
            }
            String from = state(source.from(), "transition.from");
            String to = state(source.to(), "transition.to");
            if (from.equals(to)) {
                throw new IllegalArgumentException("MVP Flow transitions cannot loop to the same state");
            }
            Trigger trigger = Trigger.compile(source.trigger());
            List<Assignment> assignments = compileAssignments(source.assignments(), variables);
            if ((trigger.type() == TriggerType.TIME || trigger.type() == TriggerType.MANUAL)
                    && assignments.stream().anyMatch(Assignment::requiresRequest)) {
                throw new IllegalArgumentException(
                        "TIME and MANUAL transitions cannot read SDK request fields");
            }
            result.add(new Transition(id, source.priority(), from, to, trigger, assignments));
        }
        return List.copyOf(result);
    }

    private static List<Assignment> compileAssignments(
            List<FixtureDefinition.FlowAssignmentDefinition> definitions,
            Map<String, Variable> variables) {
        List<Assignment> result = new ArrayList<>();
        for (FixtureDefinition.FlowAssignmentDefinition source
                : definitions == null ? List.<FixtureDefinition.FlowAssignmentDefinition>of() : definitions) {
            String variableName = safeId(source.variable(), "assignment.variable", 64);
            Variable variable = variables.get(variableName);
            if (variable == null) {
                throw new IllegalArgumentException("Assignment references undeclared variable: " + variableName);
            }
            AssignmentType type = enumValue(AssignmentType.class, source.type(), "assignment.type");
            Assignment assignment = Assignment.compile(type, variable, source);
            result.add(assignment);
        }
        return List.copyOf(result);
    }

    private static void validateGraph(String initialState, List<Transition> transitions) {
        Map<String, List<String>> edges = new HashMap<>();
        Set<String> states = new HashSet<>();
        states.add(initialState);
        transitions.forEach(transition -> {
            states.add(transition.from());
            states.add(transition.to());
            edges.computeIfAbsent(transition.from(), ignored -> new ArrayList<>()).add(transition.to());
        });
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String candidate : states) {
            detectCycle(candidate, edges, visiting, visited);
        }
    }

    private static void detectCycle(
            String state,
            Map<String, List<String>> edges,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(state)) return;
        if (!visiting.add(state)) {
            throw new IllegalArgumentException("MVP Flow graph must be acyclic");
        }
        for (String next : edges.getOrDefault(state, List.of())) {
            detectCycle(next, edges, visiting, visited);
        }
        visiting.remove(state);
        visited.add(state);
    }

    private static void validateStaticConflicts(List<Transition> transitions) {
        Set<String> deterministic = new HashSet<>();
        for (Transition transition : transitions) {
            String signature = transition.from() + '\u0000' + transition.trigger().type() + '\u0000'
                    + transition.priority() + '\u0000' + transition.trigger().staticSignature();
            if (!deterministic.add(signature)) {
                throw new IllegalArgumentException(
                        "Flow contains a provably ambiguous same-priority transition");
            }
        }
    }

    public Map<String, Object> initializeVariables(RuntimeRequest request, JsonNode body, ObjectMapper mapper) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Variable variable : variables.values()) {
            Object value = variable.initialValue().resolve(request, body).orElse(null);
            if (value != null) value = variable.normalize(value);
            if (value == null && variable.required()) {
                throw new PlatformException(
                        ErrorCode.MOCK_BUSINESS_KEY_MISSING,
                        "Required Flow initial variable is missing: " + variable.name());
            }
            if (value != null) result.put(variable.name(), value);
        }
        requireVariablesSize(result, mapper);
        return Map.copyOf(result);
    }

    public Map<String, Object> applyAssignments(
            Map<String, Object> current,
            Transition transition,
            RuntimeRequest request,
            JsonNode body,
            ObjectMapper mapper) {
        Map<String, Object> result = new LinkedHashMap<>(current);
        for (Assignment assignment : transition.assignments()) {
            assignment.apply(result, request, body);
        }
        for (Variable variable : variables.values()) {
            Object value = result.get(variable.name());
            if (value != null) result.put(variable.name(), variable.normalize(value));
            if (value == null && variable.required()) {
                throw new PlatformException(
                        ErrorCode.MOCK_TEMPLATE_RENDER_FAILED,
                        "Flow transition cleared a required variable: " + variable.name());
            }
        }
        requireVariablesSize(result, mapper);
        return Map.copyOf(result);
    }

    private static void requireVariablesSize(Map<String, Object> variables, ObjectMapper mapper) {
        try {
            if (mapper.writeValueAsBytes(variables).length > MAX_VARIABLES_JSON_BYTES) {
                throw new PlatformException(
                        ErrorCode.MOCK_TEMPLATE_RENDER_FAILED,
                        "Flow variables exceed 8KB");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new PlatformException(
                    ErrorCode.MOCK_TEMPLATE_RENDER_FAILED,
                    "Flow variables cannot be serialized",
                    failure);
        }
    }

    public Optional<Transition> dueTimeTransition(String state, String pendingTransitionId, Instant dueAt, Instant now) {
        if (pendingTransitionId == null || dueAt == null || now.isBefore(dueAt)) return Optional.empty();
        return transitions.stream()
                .filter(candidate -> candidate.transitionId().equals(pendingTransitionId))
                .filter(candidate -> candidate.from().equals(state))
                .filter(candidate -> candidate.trigger().type() == TriggerType.TIME)
                .findFirst();
    }

    public Optional<Transition> selectRequestTransition(
            String state,
            Role role,
            long queryCount,
            RuntimeRequest request,
            JsonNode body) {
        return transitions.stream()
                .filter(candidate -> candidate.from().equals(state))
                .filter(candidate -> candidate.trigger().matches(role, queryCount, request, body))
                .findFirst();
    }

    public Optional<Transition> manualTransition(String state, String transitionId) {
        return transitions.stream()
                .filter(candidate -> candidate.from().equals(state))
                .filter(candidate -> candidate.trigger().type() == TriggerType.MANUAL)
                .filter(candidate -> transitionId == null || transitionId.equals(candidate.transitionId()))
                .findFirst();
    }

    public PendingTransition pendingTransition(String state, Instant enteredAt) {
        return transitions.stream()
                .filter(candidate -> candidate.from().equals(state))
                .filter(candidate -> candidate.trigger().type() == TriggerType.TIME)
                .findFirst()
                .map(candidate -> new PendingTransition(
                        candidate.transitionId(), enteredAt.plusSeconds(candidate.trigger().delaySeconds())))
                .orElse(PendingTransition.none());
    }

    public Participant requireParticipant(String apiCode) {
        Participant participant = participants.get(apiCode);
        if (participant == null) {
            throw new PlatformException(ErrorCode.MOCK_NO_MATCH, "API is not a Participant of the fixed Flow version");
        }
        return participant;
    }

    public String flowDefinitionId() { return flowDefinitionId; }
    public String flowDefinitionVersionId() { return flowDefinitionVersionId; }
    public String provider() { return provider; }
    public String flowCode() { return flowCode; }
    public long version() { return version; }
    public String checksum() { return checksum; }
    public String initialState() { return initialState; }
    public Duration ttl() { return ttl; }
    public Map<String, Participant> participants() { return participants; }
    public Map<String, Variable> variables() { return variables; }
    public List<Transition> transitions() { return transitions; }

    public enum Role { CREATE, QUERY, COMMAND }
    public enum ValueType { STRING, NUMBER, BOOLEAN }
    public enum TriggerType { QUERY_COUNT, TIME, REQUEST_FIELD, MANUAL }
    private enum AssignmentType { SET_LITERAL, SET_FROM_REQUEST_FIELD, INCREMENT_NUMBER, CLEAR }
    private enum ValueSource { JSON_BODY, QUERY, HEADER }
    private enum Operator { EQ, NE, CONTAINS, PREFIX, REGEX, EXISTS }

    public record Participant(
            String apiCode,
            long contractVersionId,
            Role role,
            boolean createIfAbsent,
            BusinessKeyExtractor businessKeyExtractor) { }

    public record Variable(
            String name,
            ValueType type,
            boolean required,
            int maxLength,
            InitialValue initialValue) {

        Object normalize(Object value) {
            try {
                return switch (type) {
                    case STRING -> string(value);
                    case NUMBER -> number(value);
                    case BOOLEAN -> bool(value);
                };
            } catch (RuntimeException failure) {
                throw new PlatformException(
                        ErrorCode.MOCK_TEMPLATE_RENDER_FAILED,
                        "Flow variable type is invalid: " + name,
                        failure);
            }
        }

        private String string(Object value) {
            String text = value instanceof JsonNode node && node.isValueNode()
                    ? node.asText()
                    : String.valueOf(value);
            if (text.length() > maxLength) {
                throw new IllegalArgumentException("STRING variable exceeds maxLength");
            }
            return text;
        }

        private BigDecimal number(Object value) {
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof Number number) return new BigDecimal(number.toString());
            if (value instanceof JsonNode node && node.isNumber()) return node.decimalValue();
            return new BigDecimal(String.valueOf(value));
        }

        private Boolean bool(Object value) {
            if (value instanceof Boolean bool) return bool;
            if (value instanceof JsonNode node && node.isBoolean()) return node.booleanValue();
            if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
            if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
            throw new IllegalArgumentException("BOOLEAN variable is invalid");
        }
    }

    public record Transition(
            String transitionId,
            int priority,
            String from,
            String to,
            Trigger trigger,
            List<Assignment> assignments) {
        public Transition {
            assignments = List.copyOf(assignments);
        }
    }

    public record PendingTransition(String transitionId, Instant executeAt) {
        static PendingTransition none() { return new PendingTransition(null, null); }
    }

    public static final class BusinessKeyExtractor {
        private final ValueSource source;
        private final String path;
        private final boolean trim;
        private final CompiledJsonPath jsonPath;

        private BusinessKeyExtractor(ValueSource source, String path, boolean trim, CompiledJsonPath jsonPath) {
            this.source = source;
            this.path = path;
            this.trim = trim;
            this.jsonPath = jsonPath;
        }

        static BusinessKeyExtractor compile(FixtureDefinition.BusinessKeyDefinition source) {
            if (source == null || !source.required()) {
                throw new IllegalArgumentException("Flow Business Key Extractor must be required");
            }
            ValueSource valueSource = enumValue(ValueSource.class, source.source(), "businessKey.source");
            String path = requireText(source.path(), "businessKey.path");
            if (valueSource == ValueSource.HEADER && RuntimeRequest.isSensitiveHeader(path)) {
                throw new IllegalArgumentException("Sensitive header cannot be a Flow Business Key");
            }
            if (source.normalize() != null && !"TRIM".equalsIgnoreCase(source.normalize())) {
                throw new IllegalArgumentException("Flow Business Key normalize only supports TRIM");
            }
            return new BusinessKeyExtractor(
                    valueSource, path, true,
                    valueSource == ValueSource.JSON_BODY ? CompiledJsonPath.compile(path) : null);
        }

        public String extract(RuntimeRequest request, JsonNode body) {
            Optional<String> extracted = read(source, path, jsonPath, request, body);
            String value = extracted.map(candidate -> trim ? candidate.trim() : candidate)
                    .filter(candidate -> !candidate.isEmpty())
                    .orElseThrow(() -> new PlatformException(
                            ErrorCode.MOCK_BUSINESS_KEY_MISSING,
                            "Flow business key is missing"));
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BUSINESS_KEY_BYTES) {
                throw new PlatformException(
                        ErrorCode.MOCK_BUSINESS_KEY_MISSING,
                        "Flow business key exceeds 512 bytes");
            }
            return value;
        }
    }

    public record InitialValue(
            InitialSource source,
            String path,
            JsonNode literal,
            CompiledJsonPath jsonPath) {

        static InitialValue compile(FixtureDefinition.FlowVariableInitialValueDefinition source) {
            if (source == null) return new InitialValue(InitialSource.NONE, null, null, null);
            InitialSource type = enumValue(InitialSource.class, source.source(), "variable.initialValue.source");
            if (type == InitialSource.LITERAL) {
                if (source.value() == null || source.value().isContainerNode()) {
                    throw new IllegalArgumentException("Variable literal initial value must be scalar");
                }
                return new InitialValue(type, null, source.value().deepCopy(), null);
            }
            String path = requireText(source.path(), "variable.initialValue.path");
            return new InitialValue(type, path, null, CompiledJsonPath.compile(path));
        }

        Optional<Object> resolve(RuntimeRequest request, JsonNode body) {
            return switch (source) {
                case NONE -> Optional.empty();
                case LITERAL -> Optional.of(literal.deepCopy());
                case REQUEST_FIELD -> jsonPath.read(body).filter(JsonNode::isValueNode).map(value -> (Object) value);
            };
        }

        private enum InitialSource { NONE, REQUEST_FIELD, LITERAL }
    }

    public static final class Trigger {
        private final TriggerType type;
        private final long threshold;
        private final long delaySeconds;
        private final ValueSource source;
        private final String path;
        private final Operator operator;
        private final String expected;
        private final CompiledJsonPath jsonPath;
        private final Pattern regex;

        private Trigger(
                TriggerType type,
                long threshold,
                long delaySeconds,
                ValueSource source,
                String path,
                Operator operator,
                String expected,
                CompiledJsonPath jsonPath,
                Pattern regex) {
            this.type = type;
            this.threshold = threshold;
            this.delaySeconds = delaySeconds;
            this.source = source;
            this.path = path;
            this.operator = operator;
            this.expected = expected;
            this.jsonPath = jsonPath;
            this.regex = regex;
        }

        static Trigger compile(FixtureDefinition.FlowTriggerDefinition source) {
            Objects.requireNonNull(source, "Transition trigger is required");
            TriggerType type = enumValue(TriggerType.class, source.type(), "trigger.type");
            if (type == TriggerType.QUERY_COUNT) {
                if (source.threshold() == null || source.threshold() <= 0) {
                    throw new IllegalArgumentException("QUERY_COUNT threshold must be positive");
                }
                return new Trigger(type, source.threshold(), 0, null, null, null, null, null, null);
            }
            if (type == TriggerType.TIME) {
                if (source.delaySeconds() == null || source.delaySeconds() < 0
                        || source.delaySeconds() > 24 * 3_600) {
                    throw new IllegalArgumentException("TIME delaySeconds must be between 0 and 24 hours");
                }
                return new Trigger(type, 0, source.delaySeconds(), null, null, null, null, null, null);
            }
            if (type == TriggerType.MANUAL) {
                return new Trigger(type, 0, 0, null, null, null, null, null, null);
            }
            ValueSource fieldSource = enumValue(ValueSource.class, source.source(), "trigger.source");
            String path = requireText(source.path(), "trigger.path");
            if (fieldSource == ValueSource.HEADER && RuntimeRequest.isSensitiveHeader(path)) {
                throw new IllegalArgumentException("Sensitive header cannot trigger a Flow transition");
            }
            Operator operator = enumValue(Operator.class, source.operator(), "trigger.operator");
            String expected = source.value() == null || source.value().isNull()
                    ? null : comparable(source.value());
            if (operator != Operator.EXISTS && expected == null) {
                throw new IllegalArgumentException("REQUEST_FIELD expected value is required");
            }
            Pattern regex = null;
            if (operator == Operator.REGEX) {
                if (expected.length() > MAX_REGEX_LENGTH) {
                    throw new IllegalArgumentException("Flow regex exceeds 500 characters");
                }
                try {
                    regex = Pattern.compile(expected);
                } catch (PatternSyntaxException failure) {
                    throw new IllegalArgumentException("Flow regex is not valid RE2/J syntax", failure);
                }
            }
            return new Trigger(
                    type, 0, 0, fieldSource, path, operator, expected,
                    fieldSource == ValueSource.JSON_BODY ? CompiledJsonPath.compile(path) : null,
                    regex);
        }

        boolean matches(Role role, long queryCount, RuntimeRequest request, JsonNode body) {
            if (type == TriggerType.QUERY_COUNT) {
                return role == Role.QUERY && queryCount >= threshold;
            }
            if (type != TriggerType.REQUEST_FIELD) return false;
            Optional<String> actual = read(source, path, jsonPath, request, body);
            if (operator == Operator.EXISTS) return actual.isPresent();
            if (actual.isEmpty()) return false;
            String candidate = actual.get();
            return switch (operator) {
                case EQ -> candidate.equals(expected);
                case NE -> !candidate.equals(expected);
                case CONTAINS -> candidate.contains(expected);
                case PREFIX -> candidate.startsWith(expected);
                case REGEX -> regex.matches(candidate);
                case EXISTS -> true;
            };
        }

        String staticSignature() {
            return String.valueOf(threshold) + '|' + delaySeconds + '|' + source + '|' + path + '|'
                    + operator + '|' + expected;
        }

        public TriggerType type() { return type; }
        public long delaySeconds() { return delaySeconds; }
    }

    public static final class Assignment {
        private final AssignmentType type;
        private final Variable variable;
        private final JsonNode literal;
        private final ValueSource source;
        private final String path;
        private final CompiledJsonPath jsonPath;
        private final BigDecimal increment;

        private Assignment(
                AssignmentType type,
                Variable variable,
                JsonNode literal,
                ValueSource source,
                String path,
                CompiledJsonPath jsonPath,
                BigDecimal increment) {
            this.type = type;
            this.variable = variable;
            this.literal = literal;
            this.source = source;
            this.path = path;
            this.jsonPath = jsonPath;
            this.increment = increment;
        }

        static Assignment compile(
                AssignmentType type,
                Variable variable,
                FixtureDefinition.FlowAssignmentDefinition source) {
            if (type == AssignmentType.CLEAR) {
                if (variable.required()) {
                    throw new IllegalArgumentException("CLEAR cannot target a required Flow variable");
                }
                return new Assignment(type, variable, null, null, null, null, null);
            }
            if (type == AssignmentType.SET_LITERAL) {
                if (source.value() == null || source.value().isNull() || source.value().isContainerNode()) {
                    throw new IllegalArgumentException("SET_LITERAL requires a scalar value");
                }
                variable.normalize(source.value());
                return new Assignment(type, variable, source.value().deepCopy(), null, null, null, null);
            }
            if (type == AssignmentType.INCREMENT_NUMBER) {
                if (variable.type() != ValueType.NUMBER || source.increment() == null) {
                    throw new IllegalArgumentException("INCREMENT_NUMBER requires NUMBER variable and increment");
                }
                return new Assignment(
                        type, variable, null, null, null, null,
                        new BigDecimal(source.increment().toString()));
            }
            ValueSource valueSource = enumValue(ValueSource.class, source.source(), "assignment.source");
            String path = requireText(source.path(), "assignment.path");
            if (valueSource == ValueSource.HEADER && RuntimeRequest.isSensitiveHeader(path)) {
                throw new IllegalArgumentException("Sensitive header cannot be assigned to Flow variable");
            }
            return new Assignment(
                    type, variable, null, valueSource, path,
                    valueSource == ValueSource.JSON_BODY ? CompiledJsonPath.compile(path) : null,
                    null);
        }

        void apply(Map<String, Object> target, RuntimeRequest request, JsonNode body) {
            switch (type) {
                case CLEAR -> target.remove(variable.name());
                case SET_LITERAL -> target.put(variable.name(), variable.normalize(literal));
                case SET_FROM_REQUEST_FIELD -> {
                    Object value = read(source, path, jsonPath, request, body)
                            .orElseThrow(() -> new PlatformException(
                                    ErrorCode.MOCK_TEMPLATE_VARIABLE_MISSING,
                                    "Flow assignment request field is missing: " + variable.name()));
                    target.put(variable.name(), variable.normalize(value));
                }
                case INCREMENT_NUMBER -> {
                    Object current = target.get(variable.name());
                    BigDecimal number = current == null
                            ? BigDecimal.ZERO
                            : (BigDecimal) variable.normalize(current);
                    target.put(variable.name(), number.add(increment));
                }
            }
        }

        boolean requiresRequest() {
            return type == AssignmentType.SET_FROM_REQUEST_FIELD;
        }
    }

    private static Optional<String> read(
            ValueSource source,
            String path,
            CompiledJsonPath jsonPath,
            RuntimeRequest request,
            JsonNode body) {
        return switch (source) {
            case HEADER -> request.firstHeader(path);
            case QUERY -> request.firstQuery(path);
            case JSON_BODY -> jsonPath.read(body).filter(JsonNode::isValueNode).map(CompiledFlowDefinition::comparable);
        };
    }

    private static String comparable(JsonNode value) {
        return value.isTextual() ? value.textValue() : value.toString();
    }

    private static String state(String value, String name) {
        return safeId(value, name, 64);
    }

    private static String safeId(String value, String name, int maxLength) {
        if (value == null || value.length() > maxLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, requireText(value, name).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(name + " is unsupported", failure);
        }
    }
}
