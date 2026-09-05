package com.xuntian.mock.control.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.identity.OperatorContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Service
public final class ReleaseService {

    private static final Pattern RELEASE_CODE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final ReleaseMapper mapper;
    private final ReleaseSnapshotCompiler snapshotCompiler;
    private final ReleaseTransactionService transactions;
    private final RuntimeReleaseProjectionPort projection;
    private final RuntimeNodeDiscoveryPort discovery;
    private final ReleaseCompatibilityPort compatibility;
    private final RuntimeTrafficGovernancePort traffic;
    private final ReleaseIdGenerator idGenerator;
    private final CanonicalJsonCodec canonicalJson;
    private final Clock clock;

    public ReleaseService(
            ReleaseMapper mapper,
            ReleaseSnapshotCompiler snapshotCompiler,
            ReleaseTransactionService transactions,
            RuntimeReleaseProjectionPort projection,
            RuntimeNodeDiscoveryPort discovery,
            ReleaseCompatibilityPort compatibility,
            RuntimeTrafficGovernancePort traffic,
            ReleaseIdGenerator idGenerator,
            CanonicalJsonCodec canonicalJson,
            Clock clock) {
        this.mapper = mapper;
        this.snapshotCompiler = snapshotCompiler;
        this.transactions = transactions;
        this.projection = projection;
        this.discovery = discovery;
        this.compatibility = compatibility;
        this.traffic = traffic;
        this.idGenerator = idGenerator;
        this.canonicalJson = canonicalJson;
        this.clock = clock;
    }

    public List<ReleaseSummary> findAll() {
        return mapper.selectAllReleases().stream().map(this::summary).toList();
    }

    public ReleaseDetail find(String id) {
        ReleaseRecord release = require(id);
        return detail(release);
    }

    public ActiveReleaseRecord active(String environment, String app) {
        return mapper.selectActive(required(environment, "environment", 32).toUpperCase(),
                required(app, "app", 128));
    }

    public ActivationView activation(String id) {
        ReleaseActivationRecord activation = mapper.selectActivation(id);
        if (activation == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Activation not found");
        return new ActivationView(activation, mapper.selectTargets(id));
    }

    public ValidationView validate(CreateCommand command) {
        ReleaseSnapshotCompiler.Selection selection = snapshotCompiler.validateSelection(
                command.environment(), command.appCode(), command.scenarioVersionIds());
        long contracts = selection.sources().stream().map(ReleaseSourceRecord::contractVersionId).distinct().count();
        return new ValidationView(
                true, selection.environment(), selection.app(),
                selection.sources().stream().map(ReleaseSourceRecord::scenarioVersionId).toList(),
                (int) contracts, List.of());
    }

    public ReleaseDetail create(CreateCommand command, OperatorContext operator) {
        String releaseCode = releaseCode(command.releaseCode());
        String note = optional(command.releaseNote(), 512);
        ReleaseSnapshotCompiler.Selection selection = snapshotCompiler.validateSelection(
                command.environment(), command.appCode(), command.scenarioVersionIds());
        String releaseId = idGenerator.nextReleaseId();
        ReleaseSnapshotCompiler.CompiledRelease compiled = snapshotCompiler.compile(
                releaseId, clock.instant(), selection);
        ReleaseRecord release;
        try {
            release = transactions.insertPreparing(releaseCode, note, compiled, operator);
        } catch (DuplicateKeyException duplicate) {
            release = mapper.selectReleaseByCode(releaseCode);
            if (release == null || !sameSelection(release, selection)) {
                throw new PlatformException(ErrorCode.CONFLICT, "releaseCode already exists", duplicate);
            }
            if (!Set.of("PREPARING", "READY", "PUBLISHED", "PARTIAL").contains(release.status())) {
                throw new PlatformException(ErrorCode.INVALID_STATE, "Idempotent Release retry targets a failed Release");
            }
            if (!"PREPARING".equals(release.status())) return detail(release);
            compiled = compiledFromStored(release, selection.sources());
        }
        try {
            projection.putImmutableSnapshot(release.id(), compiled.canonicalEnvelopeBytes());
            byte[] readBack = projection.readImmutableSnapshot(release.id());
            if (!Arrays.equals(compiled.canonicalEnvelopeBytes(), readBack)) {
                throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                        "Immutable Snapshot read-back bytes differ");
            }
            snapshotCompiler.verifyEnvelope(readBack, release.id(), release.checksum());
            release = transactions.markReady(release.id(), operator);
            return detail(release);
        } catch (RuntimeException failure) {
            transactions.markPreparingFailed(release.id(), "Snapshot prewrite verification failed", operator);
            throw failure;
        }
    }

    public ActivationView publish(
            String releaseId,
            long expectedActivationVersion,
            OperatorContext operator) {
        return activate("PUBLISH", releaseId, expectedActivationVersion, operator);
    }

    public ActivationView rollback(
            String historicalReleaseId,
            long expectedActivationVersion,
            OperatorContext operator) {
        return activate("ROLLBACK", historicalReleaseId, expectedActivationVersion, operator);
    }

    public ActivationView waive(
            String activationId,
            String nodeId,
            String reason,
            boolean confirmed,
            OperatorContext operator) {
        if (!confirmed) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "WAIVE requires explicit confirmation");
        }
        String normalizedReason = required(reason, "reason", 512);
        ReleaseActivationRecord activation = mapper.selectActivation(activationId);
        if (activation == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Activation not found");
        if (!traffic.isRemovedFromTraffic(activation.environment(), activation.appCode(), nodeId)) {
            throw new PlatformException(ErrorCode.FORBIDDEN,
                    "Runtime node must be proven removed from traffic before WAIVE");
        }
        transactions.waive(activationId, nodeId, normalizedReason, operator);
        return activation(activationId);
    }

    public DiffView diff(String id, String compareTo) {
        ReleaseRecord current = require(id);
        ReleaseRecord base = require(compareTo);
        List<DiffEntry> changes = new ArrayList<>();
        compare("", canonicalJson.read(base.snapshotBytes()).path("snapshot"),
                canonicalJson.read(current.snapshotBytes()).path("snapshot"), changes);
        return new DiffView(compareTo, id, List.copyOf(changes));
    }

    private ActivationView activate(
            String action,
            String releaseId,
            long expectedActivationVersion,
            OperatorContext operator) {
        if (expectedActivationVersion < 0) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "expectedActivationVersion must be non-negative");
        }
        ReleaseRecord release = require(releaseId);
        verifyStoredRelease(release);
        compatibility.requireCompatible(release.environment(), release.appCode(), release.id());
        List<RuntimeNodeDiscoveryPort.RuntimeNode> nodes =
                discovery.registeredReadyNodes(release.environment(), release.appCode());
        ReleaseActivationRecord activation = transactions.activate(
                idGenerator.nextActivationId(), action, releaseId, expectedActivationVersion, nodes, operator);
        return activation(activation.id());
    }

    private void verifyStoredRelease(ReleaseRecord release) {
        snapshotCompiler.verifyEnvelope(release.snapshotBytes(), release.id(), release.checksum());
        JsonNode envelope = canonicalJson.read(release.snapshotBytes());
        if (!release.signatureKeyId().equals(envelope.path("signatureKeyId").asText())
                || !release.signatureAlgorithm().equals(envelope.path("signatureAlgorithm").asText())) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                    "Stored Release signature metadata is inconsistent");
        }
        try {
            if (!Arrays.equals(release.signature(), Base64.getDecoder().decode(envelope.path("signature").asText()))) {
                throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                        "Stored Release signature bytes are inconsistent");
            }
        } catch (IllegalArgumentException invalid) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Stored Release signature is invalid", invalid);
        }
        projection.putImmutableSnapshot(release.id(), release.snapshotBytes());
        byte[] readBack = projection.readImmutableSnapshot(release.id());
        if (!Arrays.equals(release.snapshotBytes(), readBack)) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                    "Immutable Snapshot projection could not be recovered exactly");
        }
    }

    private ReleaseSnapshotCompiler.CompiledRelease compiledFromStored(
            ReleaseRecord release,
            List<ReleaseSourceRecord> sources) {
        JsonNode envelopeNode = canonicalJson.read(release.snapshotBytes());
        try {
            ReleaseSnapshotCompiler.PublishedSnapshot snapshot = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .treeToValue(envelopeNode.path("snapshot"), ReleaseSnapshotCompiler.PublishedSnapshot.class);
            ReleaseSnapshotCompiler.SignedEnvelope envelope = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .treeToValue(envelopeNode, ReleaseSnapshotCompiler.SignedEnvelope.class);
            byte[] snapshotBytes = canonicalJson.write(envelopeNode.path("snapshot"));
            return new ReleaseSnapshotCompiler.CompiledRelease(
                    snapshot, snapshotBytes, envelope, release.snapshotBytes(), release.checksum(),
                    release.signature(), release.signatureKeyId(), release.signatureAlgorithm(), sources);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Release Snapshot cannot be parsed", failure);
        }
    }

    private boolean sameSelection(ReleaseRecord release, ReleaseSnapshotCompiler.Selection selection) {
        if (!release.environment().equals(selection.environment()) || !release.appCode().equals(selection.app())) return false;
        Set<Long> actual = new LinkedHashSet<>();
        mapper.selectReleaseItems(release.id()).stream()
                .filter(item -> "SCENARIO".equals(item.itemType()))
                .forEach(item -> actual.add(item.objectVersionId()));
        Set<Long> requested = new LinkedHashSet<>();
        selection.sources().forEach(source -> requested.add(source.scenarioVersionId()));
        return actual.equals(requested);
    }

    private ReleaseRecord require(String id) {
        ReleaseRecord release = mapper.selectRelease(required(id, "releaseId", 64));
        if (release == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Release not found");
        return release;
    }

    private ReleaseDetail detail(ReleaseRecord release) {
        return new ReleaseDetail(summary(release), canonicalJson.read(release.snapshotBytes()),
                mapper.selectReleaseItems(release.id()));
    }

    private ReleaseSummary summary(ReleaseRecord release) {
        return new ReleaseSummary(
                release.id(), release.releaseCode(), release.environment(), release.appCode(), release.status(),
                release.checksum(), release.schemaVersion(), release.signatureKeyId(), release.signatureAlgorithm(),
                release.releaseNote(), release.failureReason(), release.createdBy(), release.createdAt(),
                release.publishedBy(), release.publishedAt());
    }

    private void compare(String path, JsonNode before, JsonNode after, List<DiffEntry> changes) {
        if (before == null || before.isMissingNode()) {
            changes.add(new DiffEntry(display(path), "ADDED", before, after));
            return;
        }
        if (after == null || after.isMissingNode()) {
            changes.add(new DiffEntry(display(path), "REMOVED", before, after));
            return;
        }
        if (before.isObject() && after.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            before.fieldNames().forEachRemaining(names::add);
            after.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> compare(path + "/" + escape(name), before.get(name), after.get(name), changes));
            return;
        }
        if (before.isArray() && after.isArray()) {
            for (int index = 0; index < Math.max(before.size(), after.size()); index++) {
                compare(path + "/" + index, before.path(index), after.path(index), changes);
            }
            return;
        }
        if (!before.equals(after)) changes.add(new DiffEntry(display(path), "CHANGED", before, after));
    }

    private String releaseCode(String value) {
        if (value == null || !RELEASE_CODE.matcher(value.trim()).matches()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "releaseCode is invalid");
        }
        return value.trim();
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, field + " is invalid");
        }
        return value.trim();
    }

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        return required(value, "releaseNote", maxLength);
    }

    private String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private String display(String path) {
        return path.isEmpty() ? "/" : path;
    }

    public record CreateCommand(
            String releaseCode,
            String environment,
            String appCode,
            List<Long> scenarioVersionIds,
            String releaseNote) {
    }

    public record ValidationView(
            boolean valid,
            String environment,
            String app,
            List<Long> scenarioVersionIds,
            int contractCount,
            List<String> warnings) {
    }

    public record ReleaseSummary(
            String id,
            String releaseCode,
            String environment,
            String appCode,
            String status,
            String checksum,
            String schemaVersion,
            String signatureKeyId,
            String signatureAlgorithm,
            String releaseNote,
            String failureReason,
            String createdBy,
            Instant createdAt,
            String publishedBy,
            Instant publishedAt) {
    }

    public record ReleaseDetail(
            ReleaseSummary release,
            JsonNode signedSnapshotEnvelope,
            List<ReleaseItemRecord> items) {
    }

    public record ActivationView(
            ReleaseActivationRecord activation,
            List<ActivationTargetRecord> targets) {
    }

    public record DiffEntry(String path, String changeType, JsonNode before, JsonNode after) {
    }

    public record DiffView(String compareTo, String releaseId, List<DiffEntry> changes) {
    }
}
