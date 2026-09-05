package com.xuntian.mock.control.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.CanonicalJson;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.api.ApiService;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Service
public class ContractService {

    private static final Set<String> SOURCE_TYPES = Set.of("MANUAL", "OPENAPI", "JSON_SCHEMA");
    private static final Pattern SHA_256 = Pattern.compile("[a-fA-F0-9]{64}");
    private static final int MAX_SCHEMA_DEPTH = 64;
    private static final int MAX_SCHEMA_NODES = 100_000;

    private final ContractVersionMapper contractMapper;
    private final ApiService apiService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ContractService(
            ContractVersionMapper contractMapper,
            ApiService apiService,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.contractMapper = contractMapper;
        this.apiService = apiService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public List<ContractView> findByApi(long apiId) {
        apiService.require(apiId);
        return contractMapper.selectByApi(apiId).stream().map(this::view).toList();
    }

    @Transactional
    public ContractView create(long apiId, CreateCommand command, OperatorContext operator) {
        apiService.lockExisting(apiId);
        requireSchema(command.requestSchema(), "requestSchema");
        requireSchema(command.responseSchema(), "responseSchema");
        String sourceType = sourceType(command.sourceType());
        String sourceFileHash = sourceFileHash(command.sourceFileHash());
        String checksum = checksum(command, sourceType, sourceFileHash);
        int versionNo = contractMapper.nextVersionNo(apiId);
        try {
            contractMapper.insert(
                    apiId,
                    versionNo,
                    json(command.requestSchema()),
                    json(command.responseSchema()),
                    json(command.examples()),
                    json(command.errorCodes()),
                    json(command.businessKeyExtractor()),
                    json(command.signatureMetadata()),
                    sourceType,
                    sourceFileHash,
                    checksum,
                    operator.operatorId());
        } catch (DuplicateKeyException failure) {
            throw new PlatformException(
                    ErrorCode.CONFLICT,
                    "An identical contract version already exists for this API",
                    failure);
        }
        ContractVersionRecord created = contractMapper.selectByApiAndVersion(apiId, versionNo);
        auditService.record(
                operator,
                "CONTRACT_CREATE",
                "CONTRACT_VERSION",
                created.id(),
                checksum,
                null,
                auditView(created));
        return view(created);
    }

    @Transactional
    public ContractView validate(long id, OperatorContext operator) {
        ContractVersionRecord before = require(id);
        if ("PUBLISHED".equals(before.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Published contract cannot be modified");
        }
        if (!"DRAFT".equals(before.status()) && !"VALIDATED".equals(before.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Contract cannot be validated in current status");
        }
        validateSchema(parse(before.requestSchemaJson()), "requestSchema");
        validateSchema(parse(before.responseSchemaJson()), "responseSchema");
        if (contractMapper.markValidated(id) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Contract status changed concurrently");
        }
        ContractVersionRecord validated = require(id);
        auditService.record(
                operator,
                "CONTRACT_VALIDATE",
                "CONTRACT_VERSION",
                id,
                validated.checksum(),
                auditView(before),
                auditView(validated));
        return view(validated);
    }

    @Transactional
    public ContractView publish(long id, OperatorContext operator) {
        ContractVersionRecord before = require(id);
        if ("PUBLISHED".equals(before.status())) {
            return view(before);
        }
        if (!"VALIDATED".equals(before.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only a validated contract can be published");
        }
        if (contractMapper.publish(id, operator.operatorId()) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Contract status changed concurrently");
        }
        ContractVersionRecord published = require(id);
        auditService.record(
                operator,
                "CONTRACT_PUBLISH",
                "CONTRACT_VERSION",
                id,
                published.checksum(),
                auditView(before),
                auditView(published));
        return view(published);
    }

    public DiffResult diff(long id, long compareTo) {
        ContractVersionRecord current = require(id);
        ContractVersionRecord base = require(compareTo);
        if (current.apiId() != base.apiId()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Contracts from different APIs cannot be compared");
        }
        List<DiffEntry> changes = new ArrayList<>();
        compare("", comparable(base), comparable(current), changes);
        return new DiffResult(compareTo, id, List.copyOf(changes));
    }

    ContractVersionRecord require(long id) {
        ContractVersionRecord contract = contractMapper.selectById(id);
        if (contract == null) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "Contract version not found");
        }
        return contract;
    }

    private String checksum(CreateCommand command, String sourceType, String sourceFileHash) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestSchema", canonicalValue(command.requestSchema()));
        content.put("responseSchema", canonicalValue(command.responseSchema()));
        content.put("examples", canonicalValue(command.examples()));
        content.put("errorCodes", canonicalValue(command.errorCodes()));
        content.put("businessKeyExtractor", canonicalValue(command.businessKeyExtractor()));
        content.put("signatureMetadata", canonicalValue(command.signatureMetadata()));
        content.put("sourceType", sourceType);
        content.put("sourceFileHash", sourceFileHash);
        return Checksum.sha256Hex(CanonicalJson.write(content));
    }

    private Object canonicalValue(JsonNode value) {
        return value == null || value.isNull() ? null : objectMapper.convertValue(value, Object.class);
    }

    private void requireSchema(JsonNode schema, String field) {
        if (schema == null || !schema.isObject()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " must be a JSON object");
        }
    }

    private void validateSchema(JsonNode schema, String field) {
        requireSchema(schema, field);
        int[] nodes = {0};
        validateNode(schema, field, 0, nodes);
    }

    private void validateNode(JsonNode node, String path, int depth, int[] nodes) {
        nodes[0]++;
        if (depth > MAX_SCHEMA_DEPTH || nodes[0] > MAX_SCHEMA_NODES) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, path + " exceeds schema complexity limits");
        }
        if (node.isObject()) {
            JsonNode type = node.get("type");
            if (type != null && !type.isTextual() && !type.isArray()) {
                throw new PlatformException(ErrorCode.INVALID_REQUEST, path + ".type is invalid");
            }
            JsonNode properties = node.get("properties");
            if (properties != null && !properties.isObject()) {
                throw new PlatformException(ErrorCode.INVALID_REQUEST, path + ".properties must be an object");
            }
            JsonNode required = node.get("required");
            if (required != null && (!required.isArray() || !allText(required))) {
                throw new PlatformException(ErrorCode.INVALID_REQUEST, path + ".required must contain strings");
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            validateNode(field.getValue(), path + "." + field.getKey(), depth + 1, nodes);
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                validateNode(node.get(index), path + "[" + index + "]", depth + 1, nodes);
            }
        }
    }

    private boolean allText(JsonNode array) {
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private String sourceType(String value) {
        String normalized = value == null || value.isBlank()
                ? "MANUAL"
                : value.trim().toUpperCase(Locale.ROOT);
        if (!SOURCE_TYPES.contains(normalized)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "sourceType is invalid");
        }
        return normalized;
    }

    private String sourceFileHash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "sourceFileHash must be SHA-256 hex");
        }
        return normalized;
    }

    private String json(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Contract JSON is invalid", failure);
        }
    }

    private JsonNode parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored contract JSON is invalid", failure);
        }
    }

    private ContractView view(ContractVersionRecord contract) {
        return new ContractView(
                contract.id(),
                contract.apiId(),
                contract.versionNo(),
                contract.status(),
                parse(contract.requestSchemaJson()),
                parse(contract.responseSchemaJson()),
                parse(contract.examplesJson()),
                parse(contract.errorCodesJson()),
                parse(contract.businessKeyExtractorJson()),
                parse(contract.signatureMetadataJson()),
                contract.sourceType(),
                contract.sourceFileHash(),
                contract.checksum(),
                contract.createdBy(),
                contract.createdAt(),
                contract.publishedBy(),
                contract.publishedAt());
    }

    private Map<String, Object> auditView(ContractVersionRecord contract) {
        return Map.of(
                "id", contract.id(),
                "apiId", contract.apiId(),
                "versionNo", contract.versionNo(),
                "status", contract.status(),
                "checksum", contract.checksum(),
                "sourceType", contract.sourceType());
    }

    private JsonNode comparable(ContractVersionRecord contract) {
        Map<String, JsonNode> value = new LinkedHashMap<>();
        value.put("requestSchema", parse(contract.requestSchemaJson()));
        value.put("responseSchema", parse(contract.responseSchemaJson()));
        value.put("examples", parse(contract.examplesJson()));
        value.put("errorCodes", parse(contract.errorCodesJson()));
        value.put("businessKeyExtractor", parse(contract.businessKeyExtractorJson()));
        value.put("signatureMetadata", parse(contract.signatureMetadataJson()));
        return objectMapper.valueToTree(value);
    }

    private void compare(String path, JsonNode before, JsonNode after, List<DiffEntry> changes) {
        if (before == null || before.isMissingNode() || before.isNull()) {
            if (after != null && !after.isNull()) {
                changes.add(new DiffEntry(display(path), "ADDED", before, after));
            }
            return;
        }
        if (after == null || after.isMissingNode() || after.isNull()) {
            changes.add(new DiffEntry(display(path), "REMOVED", before, after));
            return;
        }
        if (before.isObject() && after.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            before.fieldNames().forEachRemaining(names::add);
            after.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                compare(path + "/" + escape(name), before.get(name), after.get(name), changes);
            }
            return;
        }
        if (before.isArray() && after.isArray()) {
            int size = Math.max(before.size(), after.size());
            for (int index = 0; index < size; index++) {
                compare(path + "/" + index, before.get(index), after.get(index), changes);
            }
            return;
        }
        if (!before.equals(after)) {
            changes.add(new DiffEntry(display(path), "CHANGED", before, after));
        }
    }

    private String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private String display(String path) {
        return path.isEmpty() ? "/" : path;
    }

    public record CreateCommand(
            JsonNode requestSchema,
            JsonNode responseSchema,
            JsonNode examples,
            JsonNode errorCodes,
            JsonNode businessKeyExtractor,
            JsonNode signatureMetadata,
            String sourceType,
            String sourceFileHash) {
    }

    public record ContractView(
            long id,
            long apiId,
            int versionNo,
            String status,
            JsonNode requestSchema,
            JsonNode responseSchema,
            JsonNode examples,
            JsonNode errorCodes,
            JsonNode businessKeyExtractor,
            JsonNode signatureMetadata,
            String sourceType,
            String sourceFileHash,
            String checksum,
            String createdBy,
            java.time.Instant createdAt,
            String publishedBy,
            java.time.Instant publishedAt) {
    }

    public record DiffEntry(String path, String changeType, JsonNode before, JsonNode after) {
    }

    public record DiffResult(long compareTo, long contractId, List<DiffEntry> changes) {
    }
}
