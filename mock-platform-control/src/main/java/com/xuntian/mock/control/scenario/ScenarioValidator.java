package com.xuntian.mock.control.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.api.ApiRecord;
import com.xuntian.mock.control.api.ApiService;
import com.xuntian.mock.control.contract.ContractVersionMapper;
import com.xuntian.mock.control.contract.ContractVersionRecord;
import com.xuntian.mock.control.flow.FlowDefinitionMapper;
import com.xuntian.mock.control.flow.FlowDefinitionRecord;
import com.xuntian.mock.control.flow.FlowDefinitionVersionRecord;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyBindingRecord;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyMapper;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyVersionRecord;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public final class ScenarioValidator {

    private static final Set<String> RULE_TYPES =
            Set.of("HEADER", "QUERY", "JSON_PATH", "REGEX", "BUSINESS_NO");
    private static final Set<String> OPERATORS =
            Set.of("EQ", "NE", "CONTAINS", "PREFIX", "REGEX", "EXISTS");
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-auth-token");
    private static final Set<String> VALUE_TYPES =
            Set.of("object", "array", "string", "integer", "number", "boolean", "null");
    private static final int MAX_RULES = 20;
    private static final int MAX_REGEX_LENGTH = 500;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_TEMPLATE_TOKENS = 500;

    private final ObjectMapper objectMapper;
    private final ContractVersionMapper contractMapper;
    private final ApiService apiService;
    private final ScenarioMapper scenarioMapper;
    private final ScenarioScopeAuthorizer scopeAuthorizer;
    private final FlowDefinitionMapper flowMapper;
    private final SecurityPolicyMapper securityPolicyMapper;

    public ScenarioValidator(
            ObjectMapper objectMapper,
            ContractVersionMapper contractMapper,
            ApiService apiService,
            ScenarioMapper scenarioMapper,
            ScenarioScopeAuthorizer scopeAuthorizer,
            FlowDefinitionMapper flowMapper,
            SecurityPolicyMapper securityPolicyMapper) {
        this.objectMapper = objectMapper;
        this.contractMapper = contractMapper;
        this.apiService = apiService;
        this.scenarioMapper = scenarioMapper;
        this.scopeAuthorizer = scopeAuthorizer;
        this.flowMapper = flowMapper;
        this.securityPolicyMapper = securityPolicyMapper;
    }

    public ValidationOutcome validate(
            ScenarioRecord scenario,
            ScenarioVersionRecord version,
            OperatorContext operator) {
        List<ValidationIssue> errors = new ArrayList<>();
        List<ValidationIssue> warnings = new ArrayList<>();
        JsonNode scope = parse(version.scopeJson(), "scope");
        JsonNode rules = parse(version.matchRuleJson(), "matchRules");
        JsonNode response = parse(version.responseJson(), "response");
        JsonNode callbacks = parse(version.callbackJson(), "callbacks");

        ContractVersionRecord contract = contractMapper.selectById(version.contractVersionId());
        if (contract == null) {
            error(errors, "CONTRACT_NOT_FOUND", "/contractVersionId", "Contract version does not exist");
        } else if (!"PUBLISHED".equals(contract.status())) {
            error(errors, "CONTRACT_NOT_PUBLISHED", "/contractVersionId", "Contract version must be PUBLISHED");
        } else if (contract.apiId() != scenario.apiId()) {
            error(errors, "CONTRACT_API_MISMATCH", "/contractVersionId", "Contract belongs to another API");
        }
        ApiRecord api = apiService.require(scenario.apiId());
        if (api.providerId() != scenario.providerId()) {
            error(errors, "PROVIDER_API_MISMATCH", "/apiId", "API does not belong to the Scenario provider");
        }
        if (!"ENABLED".equals(scenario.status()) || !"ENABLED".equals(api.status())) {
            error(errors, "SUBJECT_DISABLED", "/", "Scenario and API must be enabled");
        }
        FlowBinding flowBinding = validateFlowBinding(scenario, version, errors);

        ScopeValue normalizedScope = validateScope(scope, operator, errors);
        ArrayNode normalizedRules = validateRules(rules, errors);
        ObjectNode normalizedResponse = validateResponse(response, contract, errors, warnings);
        ArrayNode normalizedCallbacks = validateCallbacks(callbacks, scope, flowBinding, errors, warnings);
        detectConflict(scenario, version, normalizedScope, normalizedRules, errors);
        scanSecrets(scope, "/scope", errors);
        scanSecrets(rules, "/matchRules", errors);
        scanSecrets(response, "/response", errors);
        scanSecrets(callbacks, "/callbacks", errors);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.set("scope", normalizedScope.asJson(objectMapper));
        compiled.set("matchRules", normalizedRules);
        compiled.set("response", normalizedResponse);
        compiled.set("callbacks", normalizedCallbacks);
        if (flowBinding != null) {
            compiled.put("flowDefinitionVersionId", flowBinding.version().id());
            compiled.put("flowDefinitionChecksum", flowBinding.version().checksum());
            compiled.put("flowCode", flowBinding.definition().flowCode());
        }
        compiled.put("validatorVersion", "scenario-v1");
        return new ValidationOutcome(
                errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings), compiled);
    }

    private FlowBinding validateFlowBinding(
            ScenarioRecord scenario,
            ScenarioVersionRecord version,
            List<ValidationIssue> errors) {
        if (version.flowDefinitionVersionId() == null) return null;
        FlowDefinitionVersionRecord flowVersion = flowMapper.selectVersionById(version.flowDefinitionVersionId());
        if (flowVersion == null) {
            error(errors, "FLOW_VERSION_NOT_FOUND", "/flowDefinitionVersionId", "Flow Definition Version does not exist");
            return null;
        }
        FlowDefinitionRecord definition = flowMapper.selectById(flowVersion.flowDefinitionId());
        if (definition == null || definition.providerId() != scenario.providerId()) {
            error(errors, "FLOW_PROVIDER_MISMATCH", "/flowDefinitionVersionId", "Flow Definition belongs to another Provider");
            return null;
        }
        if (!Set.of("APPROVED", "PUBLISHED").contains(flowVersion.status())
                || !"VALID".equals(flowVersion.validationStatus()) || flowVersion.compiledJson() == null) {
            error(errors, "FLOW_VERSION_NOT_APPROVED", "/flowDefinitionVersionId",
                    "Flow Definition Version must be valid and APPROVED or PUBLISHED");
        }
        ApiRecord scenarioApi = apiService.require(scenario.apiId());
        JsonNode participants = parse(flowVersion.participantApisJson(), "stored participantApis");
        boolean participantFound = false;
        if (participants.isArray()) {
            for (JsonNode participant : participants) {
                if (scenarioApi.apiCode().equals(text(participant, "apiCode"))) {
                    participantFound = true;
                    if (participant.path("contractVersionId").asLong(-1) != version.contractVersionId()) {
                        error(errors, "FLOW_CONTRACT_MISMATCH", "/contractVersionId",
                                "Scenario Contract must equal the Participant fixed Contract Version");
                    }
                    break;
                }
            }
        }
        if (!participantFound) {
            error(errors, "FLOW_PARTICIPANT_MISSING", "/flowDefinitionVersionId",
                    "Scenario API is not a Participant of the selected Flow Version");
        }
        Set<String> states = new HashSet<>();
        states.add(flowVersion.initialState());
        JsonNode transitions = parse(flowVersion.transitionsJson(), "stored transitions");
        if (transitions.isArray()) {
            transitions.forEach(transition -> {
                states.add(text(transition, "from"));
                states.add(text(transition, "to"));
            });
        }
        states.remove("");
        return new FlowBinding(definition, flowVersion, Set.copyOf(states));
    }

    private ArrayNode validateCallbacks(
            JsonNode source,
            JsonNode scope,
            FlowBinding flowBinding,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings) {
        ArrayNode normalized = objectMapper.createArrayNode();
        if (!source.isArray() || source.size() > 20) {
            error(errors, "CALLBACKS_INVALID", "/callbacks", "callbacks must be an array with at most 20 items");
            return normalized;
        }
        if (!source.isEmpty() && flowBinding == null) {
            error(errors, "CALLBACK_FLOW_REQUIRED", "/callbacks", "Callback requires a fixed Flow Definition Version");
        }
        Set<String> definitionIds = new HashSet<>();
        boolean nonProduction = !containsEnvironment(scope, "PROD");
        for (int index = 0; index < source.size(); index++) {
            JsonNode item = source.get(index);
            String path = "/callbacks/" + index;
            if (!item.isObject()) {
                error(errors, "CALLBACK_INVALID", path, "Callback Definition must be an object");
                continue;
            }
            String definitionId = text(item, "callbackDefinitionId");
            if (definitionId.isBlank() || definitionId.length() > 128 || !definitionIds.add(definitionId)) {
                error(errors, "CALLBACK_ID", path + "/callbackDefinitionId", "callbackDefinitionId must be unique");
            }
            String triggerState = text(item, "triggerState");
            if (flowBinding != null && !flowBinding.states().contains(triggerState)) {
                error(errors, "CALLBACK_TRIGGER_STATE", path + "/triggerState", "triggerState is not part of the Flow graph");
            }
            String urlSource = text(item, "urlSource").toUpperCase(Locale.ROOT);
            if (!Set.of("FIXED", "REQUEST_FIELD").contains(urlSource)) {
                error(errors, "CALLBACK_URL_SOURCE", path + "/urlSource", "urlSource must be FIXED or REQUEST_FIELD");
            } else if ("FIXED".equals(urlSource)) {
                validateCallbackUri(text(item, "url"), nonProduction, path + "/url", errors);
            } else if (text(item, "requestField").isBlank()) {
                error(errors, "CALLBACK_REQUEST_FIELD", path + "/requestField", "requestField is required");
            }
            String method = text(item, "method").toUpperCase(Locale.ROOT);
            if (!Set.of("POST", "PUT", "PATCH").contains(method)) {
                error(errors, "CALLBACK_METHOD", path + "/method", "Callback method must be POST, PUT or PATCH");
            }
            validateCallbackHeaders(item.path("headers"), path + "/headers", errors);
            String payload = text(item, "payloadTemplate");
            if (payload.isBlank() || payload.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                error(errors, "CALLBACK_PAYLOAD", path + "/payloadTemplate", "Callback payload is required and limited to 1MB");
            }
            long delayMs = item.path("delayMs").asLong(-1);
            if (delayMs < 0 || delayMs > 86_400_000L) {
                error(errors, "CALLBACK_DELAY", path + "/delayMs", "delayMs must be from 0 to 86400000");
            }
            int maxRetry = item.path("maxRetry").asInt(-1);
            if (maxRetry < 0 || maxRetry > 3) {
                error(errors, "CALLBACK_RETRY", path + "/maxRetry", "maxRetry must be from 0 to 3");
            }
            validateSchedule(item.path("retryIntervalsMs"), maxRetry, false,
                    path + "/retryIntervalsMs", errors);
            int deliveryCount = item.path("totalDeliveryCount").asInt(-1);
            if (deliveryCount < 1 || deliveryCount > 3) {
                error(errors, "CALLBACK_DELIVERY_COUNT", path + "/totalDeliveryCount",
                        "totalDeliveryCount must be from 1 to 3");
            }
            validateSchedule(item.path("deliveryOffsetsMs"), deliveryCount, true,
                    path + "/deliveryOffsetsMs", errors);
            validateCallbackPolicy(item.path("signaturePolicyVersionId").asLong(0),
                    "CALLBACK_SIGNATURE", path + "/signaturePolicyVersionId", errors);
            validateCallbackPolicy(item.path("allowlistPolicyVersionId").asLong(0),
                    "CALLBACK_ALLOWLIST", path + "/allowlistPolicyVersionId", errors);
            if (deliveryCount > 1) {
                warnings.add(new ValidationIssue(
                        "ACTIVE_DUPLICATE_DELIVERY", path + "/totalDeliveryCount",
                        "Multiple deliveries intentionally create distinct DeliveryIds"));
            }
            normalized.add(item.deepCopy());
        }
        return normalized;
    }

    private void validateCallbackUri(
            String value,
            boolean nonProduction,
            String path,
            List<ValidationIssue> errors) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean localHttp = nonProduction && "http".equals(scheme)
                    && ("localhost".equals(host) || "127.0.0.1".equals(host));
            if (!("https".equals(scheme) || localHttp) || host.isBlank() || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                error(errors, "CALLBACK_URL", path,
                        "Callback URL must be HTTPS without user-info/fragment; local TEST may use loopback HTTP");
            }
        } catch (IllegalArgumentException failure) {
            error(errors, "CALLBACK_URL", path, "Callback URL is invalid");
        }
    }

    private void validateCallbackHeaders(JsonNode headers, String path, List<ValidationIssue> errors) {
        if (!headers.isObject()) {
            error(errors, "CALLBACK_HEADERS", path, "Callback headers must be an object");
            return;
        }
        headers.fields().forEachRemaining(entry -> {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue().isTextual() ? entry.getValue().asText() : null;
            if (entry.getKey().isBlank() || value == null || hasCrLf(entry.getKey()) || hasCrLf(value)
                    || "content-length".equals(lower) || lower.startsWith("x-mock-")
                    || SENSITIVE_HEADERS.contains(lower)) {
                error(errors, "CALLBACK_HEADER_UNSAFE", path + "/" + entry.getKey(), "Callback header is unsafe");
            }
        });
    }

    private void validateSchedule(
            JsonNode schedule,
            int expectedSize,
            boolean requireSorted,
            String path,
            List<ValidationIssue> errors) {
        if (!schedule.isArray() || expectedSize < 0 || schedule.size() != expectedSize) {
            error(errors, "CALLBACK_SCHEDULE_SIZE", path, "Schedule length must match its configured count");
            return;
        }
        long total = 0;
        long previous = -1;
        for (int index = 0; index < schedule.size(); index++) {
            JsonNode value = schedule.get(index);
            long delay = value.isIntegralNumber() ? value.asLong(-1) : -1;
            if (delay < 0 || delay > 86_400_000L) {
                error(errors, "CALLBACK_SCHEDULE_VALUE", path + "/" + index,
                        "Schedule values must be non-negative and at most 24h");
            }
            total += Math.max(0, delay);
            if (requireSorted && delay < previous) {
                error(errors, "CALLBACK_SCHEDULE_ORDER", path, "Delivery offsets must be sorted");
            }
            previous = delay;
        }
        if (total > 86_400_000L) {
            error(errors, "CALLBACK_SCHEDULE_TOTAL", path, "Callback retry schedule must fit within 24h");
        }
    }

    private void validateCallbackPolicy(
            long policyVersionId,
            String expectedType,
            String path,
            List<ValidationIssue> errors) {
        SecurityPolicyVersionRecord policy = policyVersionId > 0
                ? securityPolicyMapper.selectVersionById(policyVersionId) : null;
        if (policy == null || !expectedType.equals(policy.policyType()) || !"PUBLISHED".equals(policy.status())) {
            error(errors, "CALLBACK_POLICY_NOT_PUBLISHED", path,
                    "Callback policy Version must exist, match its type and be PUBLISHED");
            return;
        }
        SecurityPolicyBindingRecord binding = securityPolicyMapper.selectBinding(policy.policyType(), policy.scopeKey());
        if (binding == null || !"BOUND".equals(binding.status())
                || binding.desiredPolicyVersionId() != policy.id()) {
            error(errors, "CALLBACK_POLICY_NOT_BOUND", path, "Callback policy Version must be the BOUND version");
        }
    }

    private boolean containsEnvironment(JsonNode scope, String expected) {
        JsonNode environments = scope.path("environments");
        if (!environments.isArray() && scope.has("environment")) {
            return expected.equalsIgnoreCase(scope.path("environment").asText());
        }
        if (environments.isArray()) {
            for (JsonNode environment : environments) {
                if (expected.equalsIgnoreCase(environment.asText())) return true;
            }
        }
        return false;
    }

    private ScopeValue validateScope(JsonNode scope, OperatorContext operator, List<ValidationIssue> errors) {
        if (!scope.isObject()) {
            error(errors, "SCOPE_INVALID", "/scope", "Scope must be an object");
            return ScopeValue.empty();
        }
        LinkedHashSet<String> environments = strings(
                scope.has("environments") ? scope.get("environments") : singleton(scope.get("environment")),
                "/scope/environments", errors, 32);
        LinkedHashSet<String> apps = strings(scope.get("apps"), "/scope/apps", errors, 128);
        LinkedHashSet<String> tenants = strings(scope.get("tenants"), "/scope/tenants", errors, 128);
        LinkedHashSet<String> accounts = strings(scope.get("testAccounts"), "/scope/testAccounts", errors, 128);
        LinkedHashSet<String> normalizedEnvironments = new LinkedHashSet<>();
        environments.forEach(value -> normalizedEnvironments.add(value.toUpperCase(Locale.ROOT)));
        environments = normalizedEnvironments;
        if (environments.isEmpty() || apps.isEmpty()) {
            error(errors, "SCOPE_EMPTY", "/scope", "Environment and app scope must be non-empty");
        }
        if (environments.contains("PROD")) {
            error(errors, "PROD_FORBIDDEN", "/scope/environments", "PROD is not a valid mock scope");
        }
        scopeAuthorizer.requireAllowed(operator, Set.copyOf(tenants), Set.copyOf(accounts));
        return new ScopeValue(
                List.copyOf(environments), List.copyOf(apps), List.copyOf(tenants), List.copyOf(accounts));
    }

    private ArrayNode validateRules(JsonNode rules, List<ValidationIssue> errors) {
        ArrayNode normalized = objectMapper.createArrayNode();
        if (!rules.isArray()) {
            error(errors, "RULES_INVALID", "/matchRules", "Match rules must be an array");
            return normalized;
        }
        if (rules.size() > MAX_RULES) {
            error(errors, "RULE_LIMIT", "/matchRules", "A Scenario may contain at most 20 match rules");
        }
        for (int index = 0; index < rules.size(); index++) {
            JsonNode item = rules.get(index);
            String path = "/matchRules/" + index;
            if (!item.isObject()) {
                error(errors, "RULE_INVALID", path, "Match rule must be an object");
                continue;
            }
            String type = text(item, "type").toUpperCase(Locale.ROOT);
            String operator = text(item, "operator").toUpperCase(Locale.ROOT);
            String key = text(item, "key");
            String value = item.hasNonNull("value") ? item.get("value").asText() : null;
            boolean caseSensitive = item.path("caseSensitive").asBoolean(false);
            if (!RULE_TYPES.contains(type)) {
                error(errors, "RULE_TYPE", path + "/type", "Unsupported match rule type");
            }
            if (!OPERATORS.contains(operator)) {
                error(errors, "RULE_OPERATOR", path + "/operator", "Unsupported match rule operator");
            }
            if (key.isBlank() || key.length() > 512) {
                error(errors, "RULE_KEY", path + "/key", "Match rule key is required and limited to 512 characters");
            }
            if (value != null && value.length() > 4096) {
                error(errors, "RULE_VALUE", path + "/value", "Match rule value exceeds 4096 characters");
            }
            if (!"EXISTS".equals(operator) && value == null) {
                error(errors, "RULE_VALUE", path + "/value", "Match rule value is required");
            }
            if ("REGEX".equals(type) && !"REGEX".equals(operator)) {
                error(errors, "REGEX_OPERATOR", path + "/operator", "REGEX type requires REGEX operator");
            }
            if ("HEADER".equals(type) && SENSITIVE_HEADERS.contains(key.toLowerCase(Locale.ROOT))) {
                error(errors, "SENSITIVE_HEADER", path + "/key", "Sensitive headers cannot be match inputs");
            }
            if ("JSON_PATH".equals(type)) {
                validateJsonPath(key, path + "/key", errors);
            }
            if ("REGEX".equals(type)) {
                validateRegexSource(key, path + "/key", errors);
            }
            if ("REGEX".equals(type) || "REGEX".equals(operator)) {
                validateRegex(value, caseSensitive, path + "/value", errors);
            }
            ObjectNode target = normalized.addObject();
            target.put("type", type);
            target.put("operator", operator);
            target.put("key", key);
            if (value == null) target.putNull("value"); else target.put("value", value);
            target.put("caseSensitive", caseSensitive);
        }
        return normalized;
    }

    private ObjectNode validateResponse(
            JsonNode response,
            ContractVersionRecord contract,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings) {
        ObjectNode normalized = objectMapper.createObjectNode();
        if (!response.isObject()) {
            error(errors, "RESPONSE_INVALID", "/response", "Response must be an object");
            return normalized;
        }
        int httpStatus = response.path("httpStatus").asInt(0);
        if (httpStatus < 100 || httpStatus > 599) {
            error(errors, "HTTP_STATUS", "/response/httpStatus", "HTTP status must be from 100 to 599");
        }
        normalized.put("httpStatus", httpStatus);
        ObjectNode headers = objectMapper.createObjectNode();
        JsonNode sourceHeaders = response.get("headers");
        if (sourceHeaders != null && !sourceHeaders.isNull()) {
            if (!sourceHeaders.isObject()) {
                error(errors, "HEADERS_INVALID", "/response/headers", "Headers must be an object");
            } else {
                sourceHeaders.fields().forEachRemaining(entry -> {
                    String lower = entry.getKey().toLowerCase(Locale.ROOT);
                    String value = entry.getValue().isTextual() ? entry.getValue().asText() : null;
                    if (entry.getKey().isBlank() || entry.getKey().length() > 128 || value == null
                            || value.length() > 4096 || hasCrLf(entry.getKey()) || hasCrLf(value)
                            || "content-length".equals(lower) || lower.startsWith("x-mock-")
                            || SENSITIVE_HEADERS.contains(lower)) {
                        error(errors, "HEADER_UNSAFE", "/response/headers/" + entry.getKey(),
                                "Response header is unsafe");
                    } else {
                        headers.put(entry.getKey(), value);
                    }
                });
            }
        }
        normalized.set("headers", headers);
        String template = text(response, "bodyTemplate");
        normalized.put("bodyTemplate", template);
        ObjectNode defaults = response.path("variableDefaults").isObject()
                ? (ObjectNode) response.path("variableDefaults").deepCopy()
                : objectMapper.createObjectNode();
        if (response.has("variableDefaults") && !response.get("variableDefaults").isObject()) {
            error(errors, "DEFAULTS_INVALID", "/response/variableDefaults", "variableDefaults must be an object");
        }
        defaults.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                error(errors, "DEFAULT_VALUE_INVALID", "/response/variableDefaults/" + entry.getKey(),
                        "Runtime variableDefaults values must be strings");
            }
        });
        normalized.set("variableDefaults", defaults);
        long delayMs = response.path("delayMs").asLong(0);
        if (delayMs < 0 || delayMs > 60_000) {
            error(errors, "RESPONSE_DELAY", "/response/delayMs", "delayMs must be from 0 to 60000");
        }
        normalized.put("delayMs", delayMs);
        ObjectNode fault = normalizeFault(response.path("fault"), httpStatus, errors);
        normalized.set("fault", fault);
        JsonNode rendered = renderTemplate(template, defaults, errors);
        if (rendered != null && contract != null && contract.responseSchemaJson() != null) {
            JsonNode schema = parse(contract.responseSchemaJson(), "stored response schema");
            validateSchemaValue(rendered, schema, "/response/sample", errors, 0);
        } else if (rendered == null && errors.stream().noneMatch(issue -> issue.path().startsWith("/response/bodyTemplate"))) {
            warnings.add(new ValidationIssue(
                    "RESPONSE_SAMPLE_UNAVAILABLE", "/response/bodyTemplate",
                    "Response schema validation could not produce a sample"));
        }
        return normalized;
    }

    private ObjectNode normalizeFault(JsonNode source, int httpStatus, List<ValidationIssue> errors) {
        ObjectNode normalized = objectMapper.createObjectNode();
        if (source.isMissingNode() || source.isNull()) {
            normalized.put("type", "NONE");
            normalized.put("durationMs", 0);
            normalized.put("sideEffectPolicy", "APPLY_BEFORE_FAULT");
            return normalized;
        }
        if (!source.isObject()) {
            error(errors, "FAULT_INVALID", "/response/fault", "fault must be an object");
            return normalized;
        }
        String type = text(source, "type").toUpperCase(Locale.ROOT);
        String policy = text(source, "sideEffectPolicy").toUpperCase(Locale.ROOT);
        long durationMs = source.path("durationMs").asLong(0);
        if (!Set.of("NONE", "HTTP_ERROR", "READ_TIMEOUT", "CONNECTION_RESET").contains(type)) {
            error(errors, "FAULT_TYPE", "/response/fault/type", "Unsupported fault type");
        }
        if (!Set.of("NO_APPLY", "APPLY_BEFORE_FAULT").contains(policy)) {
            error(errors, "FAULT_SIDE_EFFECT", "/response/fault/sideEffectPolicy",
                    "sideEffectPolicy must be NO_APPLY or APPLY_BEFORE_FAULT");
        }
        if (durationMs < 0 || durationMs > 60_000
                || (("NONE".equals(type) || "CONNECTION_RESET".equals(type)) && durationMs != 0)
                || ("READ_TIMEOUT".equals(type) && durationMs == 0)) {
            error(errors, "FAULT_DURATION", "/response/fault/durationMs", "Fault duration is invalid");
        }
        if ("HTTP_ERROR".equals(type) && httpStatus < 400) {
            error(errors, "FAULT_HTTP_STATUS", "/response/httpStatus",
                    "HTTP_ERROR requires a 4xx or 5xx response status");
        }
        normalized.put("type", type);
        normalized.put("durationMs", durationMs);
        normalized.put("sideEffectPolicy", policy);
        return normalized;
    }

    private JsonNode renderTemplate(String template, ObjectNode defaults, List<ValidationIssue> errors) {
        if (template == null || template.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            error(errors, "TEMPLATE_SIZE", "/response/bodyTemplate", "Response template is required and limited to 1MB");
            return null;
        }
        StringBuilder output = new StringBuilder(template.length());
        int cursor = 0;
        int tokenCount = 0;
        boolean inString = false;
        boolean escaped = false;
        while (cursor < template.length()) {
            int start = template.indexOf("${", cursor);
            int literalEnd = start < 0 ? template.length() : start;
            String literal = template.substring(cursor, literalEnd);
            output.append(literal);
            for (int index = 0; index < literal.length(); index++) {
                char current = literal.charAt(index);
                if (escaped) escaped = false;
                else if (current == '\\' && inString) escaped = true;
                else if (current == '"') inString = !inString;
            }
            if (start < 0) break;
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                error(errors, "TEMPLATE_TOKEN", "/response/bodyTemplate", "Template contains an unclosed token");
                return null;
            }
            String expression = template.substring(start + 2, end);
            boolean typed = expression.startsWith("json:");
            String token = typed ? expression.substring(5) : expression;
            if (!validTemplateToken(token)) {
                error(errors, "TEMPLATE_TOKEN", "/response/bodyTemplate", "Unsupported template token: " + token);
                return null;
            }
            if (typed == inString) {
                error(errors, "TEMPLATE_POSITION", "/response/bodyTemplate",
                        typed ? "json: token must be outside a JSON string" : "String token must be inside a JSON string");
                return null;
            }
            JsonNode sample = defaults.get(token);
            if (sample == null) sample = builtInSample(token);
            if (sample == null) {
                error(errors, "TEMPLATE_SAMPLE", "/response/variableDefaults/" + token,
                        "A validation sample/default is required for this token");
                return null;
            }
            try {
                String encoded = objectMapper.writeValueAsString(sample.isValueNode() ? sample.asText() : sample);
                if (typed) {
                    output.append(objectMapper.writeValueAsString(sample));
                } else {
                    output.append(encoded, 1, encoded.length() - 1);
                }
            } catch (JsonProcessingException failure) {
                error(errors, "TEMPLATE_SAMPLE", "/response/variableDefaults/" + token,
                        "Template sample cannot be serialized");
                return null;
            }
            if (++tokenCount > MAX_TEMPLATE_TOKENS) {
                error(errors, "TEMPLATE_TOKEN_LIMIT", "/response/bodyTemplate", "Template exceeds 500 tokens");
                return null;
            }
            cursor = end + 1;
        }
        try {
            return objectMapper.readTree(output.toString());
        } catch (JsonProcessingException failure) {
            error(errors, "TEMPLATE_JSON", "/response/bodyTemplate", "Rendered sample is not valid JSON");
            return null;
        }
    }

    private boolean validTemplateToken(String token) {
        if (token == null || token.isBlank() || token.length() > 256) return false;
        boolean valid = token.equals("flow.businessNo") || token.equals("state.current")
                || token.equals("traceId") || token.equals("mockRequestId")
                || token.equals("now.iso") || token.equals("now.epochMillis") || token.equals("uuid")
                || token.startsWith("request.body.$.") || token.startsWith("request.header.")
                || token.startsWith("request.query.") || token.startsWith("flow.variable.");
        if (token.startsWith("request.header.")) {
            String name = token.substring("request.header.".length());
            return valid && !name.isBlank() && !SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
        }
        if (token.startsWith("request.body.")) {
            return valid && jsonPathValid(token.substring("request.body.".length()));
        }
        return valid;
    }

    private JsonNode builtInSample(String token) {
        if (token.equals("now.epochMillis")) return objectMapper.valueToTree(0L);
        if (token.equals("now.iso")) return objectMapper.valueToTree("2000-01-01T00:00:00Z");
        if (token.equals("uuid")) return objectMapper.valueToTree("00000000-0000-0000-0000-000000000000");
        if (token.equals("traceId") || token.equals("mockRequestId")) return objectMapper.valueToTree("validation-sample");
        return null;
    }

    private void validateSchemaValue(
            JsonNode value,
            JsonNode schema,
            String path,
            List<ValidationIssue> errors,
            int depth) {
        if (depth > 64 || schema == null || !schema.isObject()) return;
        String type = schema.path("type").isTextual() ? schema.path("type").asText() : null;
        if (type != null && VALUE_TYPES.contains(type) && !matchesType(value, type)) {
            error(errors, "RESPONSE_SCHEMA", path, "Rendered response does not satisfy schema type " + type);
            return;
        }
        if (value.isObject()) {
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                required.forEach(name -> {
                    if (name.isTextual() && !value.has(name.asText())) {
                        error(errors, "RESPONSE_SCHEMA", path + "/" + name.asText(), "Required response property is missing");
                    }
                });
            }
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                properties.fields().forEachRemaining(entry -> {
                    if (value.has(entry.getKey())) {
                        validateSchemaValue(value.get(entry.getKey()), entry.getValue(),
                                path + "/" + entry.getKey(), errors, depth + 1);
                    }
                });
            }
        }
        if (value.isArray() && schema.path("items").isObject()) {
            for (int index = 0; index < value.size(); index++) {
                validateSchemaValue(value.get(index), schema.path("items"), path + "/" + index, errors, depth + 1);
            }
        }
    }

    private boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private void detectConflict(
            ScenarioRecord scenario,
            ScenarioVersionRecord current,
            ScopeValue scope,
            ArrayNode rules,
            List<ValidationIssue> errors) {
        for (ScenarioVersionRecord candidate : scenarioMapper.selectConflictCandidates(scenario.apiId(), current.id())) {
            if (candidate.priority() != current.priority()
                    || !timeOverlaps(current.effectiveFrom(), current.effectiveTo(), candidate.effectiveFrom(), candidate.effectiveTo())) {
                continue;
            }
            JsonNode candidateScope = parse(candidate.scopeJson(), "stored scope");
            ScopeValue other = ScopeValue.from(candidateScope);
            if (scope.overlaps(other) && rules.equals(parse(candidate.matchRuleJson(), "stored match rules"))) {
                error(errors, "SCENARIO_CONFLICT", "/matchRules",
                        "Same-priority Scenario can match the same request as version " + candidate.id());
            }
        }
    }

    private boolean timeOverlaps(Instant aFrom, Instant aTo, Instant bFrom, Instant bTo) {
        return (aTo == null || bFrom == null || aTo.isAfter(bFrom))
                && (bTo == null || aFrom == null || bTo.isAfter(aFrom));
    }

    private void validateJsonPath(String value, String path, List<ValidationIssue> errors) {
        if (!jsonPathValid(value)) {
            error(errors, "JSON_PATH", path, "JSONPath must use supported $.field[index] syntax");
        }
    }

    private boolean jsonPathValid(String value) {
        return value != null && value.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+])*");
    }

    private void validateRegexSource(String source, String path, List<ValidationIssue> errors) {
        if (source == null) {
            error(errors, "REGEX_SOURCE", path, "REGEX source is required");
            return;
        }
        int separator = source.indexOf(':');
        if (separator <= 0 || separator == source.length() - 1) {
            error(errors, "REGEX_SOURCE", path, "Use HEADER:name, QUERY:name, JSON_PATH:$.path or BUSINESS_NO:value");
            return;
        }
        String type = source.substring(0, separator).toUpperCase(Locale.ROOT);
        String key = source.substring(separator + 1);
        if (!Set.of("HEADER", "QUERY", "JSON_PATH", "BUSINESS_NO").contains(type)) {
            error(errors, "REGEX_SOURCE", path, "REGEX source type is unsupported");
        } else if ("JSON_PATH".equals(type)) {
            validateJsonPath(key, path, errors);
        } else if ("HEADER".equals(type) && SENSITIVE_HEADERS.contains(key.toLowerCase(Locale.ROOT))) {
            error(errors, "SENSITIVE_HEADER", path, "Sensitive headers cannot be REGEX inputs");
        }
    }

    private void validateRegex(String value, boolean caseSensitive, String path, List<ValidationIssue> errors) {
        if (value == null || value.length() > MAX_REGEX_LENGTH) {
            error(errors, "REGEX_LENGTH", path, "Regex is required and limited to 500 characters");
            return;
        }
        try {
            Pattern.compile(caseSensitive ? value : "(?i:" + value + ")");
        } catch (PatternSyntaxException invalid) {
            error(errors, "REGEX_SYNTAX", path, "Regex is not valid RE2/J syntax");
        }
    }

    private void scanSecrets(JsonNode node, String path, List<ValidationIssue> errors) {
        if (node == null) return;
        if (node.isTextual()) {
            String value = node.asText();
            if (value.contains("-----BEGIN PRIVATE KEY-----")
                    || value.matches("(?s).*AKIA[0-9A-Z]{16}.*")
                    || value.matches("(?s).*eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}.*")) {
                error(errors, "SECRET_DETECTED", path, "Known secret material is forbidden");
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                scanSecrets(field.getValue(), path + "/" + field.getKey(), errors);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) scanSecrets(node.get(index), path + "/" + index, errors);
        }
    }

    private LinkedHashSet<String> strings(
            JsonNode value,
            String path,
            List<ValidationIssue> errors,
            int maxLength) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null || value.isNull()) return result;
        if (!value.isArray()) {
            error(errors, "SCOPE_LIST", path, "Scope value must be an array");
            return result;
        }
        if (value.size() > 1000) {
            error(errors, "SCOPE_LIMIT", path, "Scope list exceeds 1000 values");
            return result;
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > maxLength || !result.add(item.asText())) {
                error(errors, "SCOPE_VALUE", path, "Scope values must be unique, non-blank strings");
            }
        }
        return result;
    }

    private ArrayNode singleton(JsonNode value) {
        ArrayNode result = objectMapper.createArrayNode();
        if (value != null && !value.isNull()) result.add(value);
        return result;
    }

    private JsonNode parse(String value, String field) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, field + " JSON is corrupt", failure);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private boolean hasCrLf(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private void error(List<ValidationIssue> errors, String code, String path, String message) {
        errors.add(new ValidationIssue(code, path, message));
    }

    public record ValidationOutcome(
            boolean valid,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings,
            JsonNode compiled) {
    }

    public record ValidationIssue(String code, String path, String message) {
    }

    private record FlowBinding(
            FlowDefinitionRecord definition,
            FlowDefinitionVersionRecord version,
            Set<String> states) {
    }

    private record ScopeValue(
            List<String> environments,
            List<String> apps,
            List<String> tenants,
            List<String> testAccounts) {

        static ScopeValue empty() {
            return new ScopeValue(List.of(), List.of(), List.of(), List.of());
        }

        static ScopeValue from(JsonNode value) {
            if (value == null || !value.isObject()) return empty();
            return new ScopeValue(
                    values(value, "environments", "environment"), values(value, "apps", null),
                    values(value, "tenants", null), values(value, "testAccounts", null));
        }

        private static List<String> values(JsonNode value, String arrayName, String singularName) {
            JsonNode array = value.get(arrayName);
            if (array != null && array.isArray()) {
                List<String> values = new ArrayList<>();
                array.forEach(item -> { if (item.isTextual()) values.add(item.asText()); });
                return values;
            }
            JsonNode singular = singularName == null ? null : value.get(singularName);
            return singular != null && singular.isTextual() ? List.of(singular.asText()) : List.of();
        }

        boolean overlaps(ScopeValue other) {
            return intersects(environments, other.environments)
                    && intersects(apps, other.apps)
                    && optionalIntersects(tenants, other.tenants)
                    && optionalIntersects(testAccounts, other.testAccounts);
        }

        private boolean intersects(List<String> left, List<String> right) {
            Set<String> values = new HashSet<>(left);
            return right.stream().anyMatch(values::contains);
        }

        private boolean optionalIntersects(List<String> left, List<String> right) {
            return left.isEmpty() || right.isEmpty() || intersects(left, right);
        }

        ObjectNode asJson(ObjectMapper mapper) {
            ObjectNode result = mapper.createObjectNode();
            result.set("environments", mapper.valueToTree(environments));
            result.set("apps", mapper.valueToTree(apps));
            result.set("tenants", mapper.valueToTree(tenants));
            result.set("testAccounts", mapper.valueToTree(testAccounts));
            return result;
        }
    }
}
