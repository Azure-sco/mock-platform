package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.audit.AuditService;
import com.xuntian.mock.control.identity.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReleaseTransactionService {

    private static final int MAX_OUTBOX_ATTEMPTS = 10;
    private final ReleaseMapper mapper;
    private final AuditService auditService;
    private final CanonicalJsonCodec canonicalJson;
    private final Clock clock;

    public ReleaseTransactionService(
            ReleaseMapper mapper,
            AuditService auditService,
            CanonicalJsonCodec canonicalJson,
            Clock clock) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.canonicalJson = canonicalJson;
        this.clock = clock;
    }

    @Transactional
    public ReleaseRecord insertPreparing(
            String releaseCode,
            String releaseNote,
            ReleaseSnapshotCompiler.CompiledRelease compiled,
            OperatorContext operator) {
        ReleaseSnapshotCompiler.PublishedSnapshot snapshot = compiled.snapshot();
        mapper.insertRelease(
                snapshot.releaseId(), releaseCode, snapshot.environment(), snapshot.app(),
                canonicalJson.text(compiled.canonicalEnvelopeBytes()), compiled.canonicalEnvelopeBytes(),
                compiled.checksum(), snapshot.schemaVersion(), compiled.signature(),
                compiled.signatureKeyId(), compiled.signatureAlgorithm(), releaseNote,
                operator.operatorId(), snapshot.createdAt());
        Set<Long> contracts = new LinkedHashSet<>();
        for (ReleaseSourceRecord source : compiled.sources()) {
            if (contracts.add(source.contractVersionId())) {
                mapper.insertReleaseItem(
                        snapshot.releaseId(), "CONTRACT", source.apiId(), source.contractVersionId());
            }
            mapper.insertReleaseItem(
                    snapshot.releaseId(), "SCENARIO", source.scenarioId(), source.scenarioVersionId());
        }
        ReleaseRecord created = requireRelease(snapshot.releaseId());
        auditService.record(
                operator, "RELEASE_PREPARE", "RELEASE", created.id(), created.checksum(),
                null, auditRelease(created));
        return created;
    }

    @Transactional
    public ReleaseRecord markReady(String releaseId, OperatorContext operator) {
        ReleaseRecord before = mapper.lockRelease(releaseId);
        if (before == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Release not found");
        if ("READY".equals(before.status())) return before;
        if (mapper.markReleaseReady(releaseId) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Release cannot become READY in current state");
        }
        ReleaseRecord updated = requireRelease(releaseId);
        auditService.record(operator, "RELEASE_READY", "RELEASE", releaseId, updated.checksum(),
                auditRelease(before), auditRelease(updated));
        return updated;
    }

    @Transactional
    public void markPreparingFailed(String releaseId, String reason, OperatorContext operator) {
        ReleaseRecord before = mapper.lockRelease(releaseId);
        if (before == null || !"PREPARING".equals(before.status())) return;
        String masked = mask(reason);
        mapper.markReleaseFailed(releaseId, masked);
        ReleaseRecord updated = requireRelease(releaseId);
        auditService.record(operator, "RELEASE_PREPARE_FAILED", "RELEASE", releaseId, before.checksum(),
                auditRelease(before), auditRelease(updated));
    }

    @Transactional
    public ReleaseActivationRecord activate(
            String activationId,
            String action,
            String releaseId,
            long expectedActivationVersion,
            List<RuntimeNodeDiscoveryPort.RuntimeNode> capturedNodes,
            OperatorContext operator) {
        ReleaseRecord candidate = mapper.selectRelease(releaseId);
        if (candidate == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Release not found");
        mapper.ensureActive(candidate.environment(), candidate.appCode());
        ActiveReleaseRecord active = mapper.lockActive(candidate.environment(), candidate.appCode());

        ReleaseActivationRecord retry = mapper.selectActivationByRequestId(operator.requestId());
        if (retry != null) {
            if (!retry.toReleaseId().equals(releaseId) || !retry.action().equals(action)) {
                throw new PlatformException(ErrorCode.CONFLICT, "requestId was already used for another activation");
            }
            return retry;
        }
        if (active.activationVersion() != expectedActivationVersion) {
            throw new PlatformException(ErrorCode.CONFLICT,
                    "expectedActivationVersion does not match the MySQL authority");
        }
        if ("ACTIVATING".equals(active.state())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "An Activation is still in progress");
        }
        if ("PUBLISH".equals(action) && "PARTIAL".equals(active.state())) {
            throw new PlatformException(ErrorCode.INVALID_STATE,
                    "A PARTIAL Activation blocks a new publish; recover, waive or rollback first");
        }
        ReleaseRecord lockedCandidate = mapper.lockRelease(releaseId);
        Set<String> accepted = "PUBLISH".equals(action)
                ? Set.of("READY")
                : Set.of("READY", "PUBLISHED");
        if (!accepted.contains(lockedCandidate.status())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Release cannot be activated in current state");
        }
        if (releaseId.equals(active.releaseId())) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Release is already active");
        }
        List<RuntimeNodeDiscoveryPort.RuntimeNode> nodes = normalizeNodes(capturedNodes);
        long newVersion = Math.addExact(active.activationVersion(), 1L);
        Instant now = clock.instant();
        Instant deadline = now.plusSeconds(5);
        ReleasePointer pointer = new ReleasePointer(
                releaseId, newVersion, lockedCandidate.checksum(), lockedCandidate.signatureKeyId());
        byte[] pointerBytes = canonicalJson.write(pointer);
        if (mapper.activate(
                candidate.environment(), candidate.appCode(), releaseId,
                expectedActivationVersion, newVersion, now) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Active Release changed concurrently");
        }
        mapper.insertActivation(
                activationId, candidate.environment(), candidate.appCode(), active.releaseId(), releaseId,
                active.activationVersion(), newVersion, action, operator.requestId(), operator.operatorId(),
                deadline, now);
        if (!nodes.isEmpty()) mapper.insertTargets(activationId, nodes, now);
        mapper.insertOutbox(
                activationId,
                candidate.environment() + ":" + candidate.appCode(),
                newVersion,
                canonicalJson.text(pointerBytes),
                pointerBytes,
                now);
        ReleaseActivationRecord activation = mapper.selectActivation(activationId);
        auditService.record(
                operator, "RELEASE_" + action, "RELEASE_ACTIVATION", activationId,
                lockedCandidate.checksum(), null, auditActivation(activation));
        return activation;
    }

    @Transactional
    public ReleaseOutboxRecord claimOutbox(String worker, Duration lease) {
        Instant now = clock.instant();
        ReleaseOutboxRecord candidate = mapper.lockNextOutbox(now);
        if (candidate == null) return null;
        if (mapper.claimOutbox(candidate.id(), worker, now.plus(lease), candidate.fencingToken()) != 1) {
            return null;
        }
        return mapper.selectOutbox(candidate.id());
    }

    @Transactional
    public void finishProjection(long outboxId, String worker, long fence) {
        ReleaseOutboxRecord initial = mapper.selectOutbox(outboxId);
        if (initial == null) return;
        ReleaseActivationRecord hint = mapper.selectActivation(initial.activationId());
        if (hint == null) throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Outbox Activation is missing");
        ActiveReleaseRecord active = mapper.lockActive(hint.environment(), hint.appCode());
        ReleaseActivationRecord activation = mapper.lockActivation(hint.id());
        List<ActivationTargetRecord> targets = mapper.lockTargets(activation.id());
        ReleaseOutboxRecord outbox = mapper.lockOutbox(outboxId);
        if (outbox == null || !worker.equals(outbox.leaseOwner()) || fence != outbox.fencingToken()
                || !"NEW".equals(outbox.status())) {
            throw new PlatformException(ErrorCode.CONFLICT, "Outbox lease was fenced by another worker");
        }
        if (active.activationVersion() != activation.toActivationVersion()
                || !active.releaseId().equals(activation.toReleaseId())) {
            // A newer authority exists. The monotonic Redis writer ignored this stale projection.
            if (mapper.finishOutbox(outboxId, worker, fence, clock.instant()) != 1) {
                throw new PlatformException(ErrorCode.CONFLICT, "Outbox lease was fenced by another worker");
            }
            return;
        }
        Instant now = clock.instant();
        if (mapper.finishOutbox(outboxId, worker, fence, now) != 1) {
            throw new PlatformException(ErrorCode.CONFLICT, "Outbox lease was fenced by another worker");
        }
        if ("PENDING".equals(activation.status())) {
            mapper.updateActivationStatus(activation.id(), "PROJECTED", null);
            activation = mapper.lockActivation(activation.id());
        }
        completeIfConverged(active, activation, targets, now);
    }

    @Transactional
    public void failProjection(long outboxId, String worker, long fence, Throwable failure) {
        ReleaseOutboxRecord outbox = mapper.lockOutbox(outboxId);
        if (outbox == null || !worker.equals(outbox.leaseOwner()) || fence != outbox.fencingToken()
                || !"NEW".equals(outbox.status())) return;
        boolean terminal = outbox.attemptCount() >= MAX_OUTBOX_ATTEMPTS;
        long seconds = Math.min(60L, 1L << Math.min(6, Math.max(0, outbox.attemptCount() - 1)));
        mapper.failOutbox(
                outboxId, worker, fence, terminal ? "FAILED" : "NEW",
                clock.instant().plusSeconds(seconds), mask(failure.getClass().getSimpleName()));
    }

    @Transactional
    public AckResult acknowledge(AckCommand command) {
        ReleaseActivationRecord hint = mapper.selectActivationByVersion(
                command.environment(), command.app(), command.activationVersion());
        if (hint == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Activation not found");
        ActiveReleaseRecord active = mapper.lockActive(command.environment(), command.app());
        if (active == null || active.activationVersion() != command.activationVersion()
                || !command.releaseId().equals(active.releaseId())) {
            throw new PlatformException(ErrorCode.CONFLICT, "ACK does not match the active MySQL authority");
        }
        ReleaseActivationRecord activation = mapper.lockActivation(hint.id());
        List<ActivationTargetRecord> targets = mapper.lockTargets(activation.id());
        Instant now = clock.instant();
        mapper.upsertAck(
                command.environment(), command.app(), command.nodeId(), command.releaseId(),
                command.activationVersion(), command.status(), command.errorMasked(), now);
        mapper.updateTargetAck(activation.id(), command.nodeId(), command.status(), now);
        targets = mapper.lockTargets(activation.id());
        completeIfConverged(active, activation, targets, now);
        ReleaseActivationRecord updated = mapper.selectActivation(activation.id());
        return new AckResult(updated.id(), updated.status(), updated.toActivationVersion());
    }

    @Transactional
    public ReleaseActivationRecord markPartial(String activationId, Set<String> removedNodeIds) {
        ReleaseActivationRecord hint = mapper.selectActivation(activationId);
        if (hint == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Activation not found");
        ActiveReleaseRecord active = mapper.lockActive(hint.environment(), hint.appCode());
        ReleaseActivationRecord activation = mapper.lockActivation(activationId);
        List<ActivationTargetRecord> targets = mapper.lockTargets(activationId);
        if (Set.of("APPLIED", "PARTIAL").contains(activation.status())) return activation;
        if (clock.instant().isBefore(activation.deadlineAt())) return activation;
        List<String> nonReady = targets.stream()
                .filter(ActivationTargetRecord::required)
                .filter(target -> !"READY".equals(target.status()))
                .map(ActivationTargetRecord::runtimeNodeId)
                .toList();
        if (nonReady.isEmpty()) {
            completeIfConverged(active, activation, targets, clock.instant());
            return mapper.selectActivation(activationId);
        }
        if (!removedNodeIds.containsAll(nonReady)) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                    "All non-ready Runtime nodes must be removed from traffic before PARTIAL");
        }
        Instant now = clock.instant();
        mapper.updateActivationStatus(activationId, "PARTIAL", null);
        mapper.updateActiveState(
                activation.environment(), activation.appCode(), activation.toReleaseId(),
                activation.toActivationVersion(), "PARTIAL", now);
        if ("PUBLISH".equals(activation.action())) {
            mapper.markReleasePartial(activation.toReleaseId(), "Runtime nodes missed the 5-second convergence window");
        }
        OperatorContext system = systemOperator(activation, "partial");
        auditService.record(system, "RELEASE_ACTIVATION_PARTIAL", "RELEASE_ACTIVATION", activationId,
                null, auditActivation(activation), auditActivation(mapper.selectActivation(activationId)));
        return mapper.selectActivation(activationId);
    }

    @Transactional
    public ReleaseActivationRecord markLeft(String activationId, String nodeId) {
        ReleaseActivationRecord hint = mapper.selectActivation(activationId);
        if (hint == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Activation not found");
        ActiveReleaseRecord active = mapper.lockActive(hint.environment(), hint.appCode());
        ReleaseActivationRecord activation = mapper.lockActivation(activationId);
        List<ActivationTargetRecord> targets = mapper.lockTargets(activationId);
        ActivationTargetRecord target = target(targets, nodeId);
        if (!target.required() || Set.of("READY", "LEFT", "WAIVED").contains(target.status())) return activation;
        Instant now = clock.instant();
        if (mapper.markTargetLeft(activationId, nodeId, now) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Target changed concurrently");
        }
        OperatorContext system = systemOperator(activation, "left-" + nodeId);
        auditService.record(system, "RELEASE_TARGET_LEFT", "ACTIVATION_TARGET", target.id(), null,
                auditTarget(target), auditTarget(target(mapper.lockTargets(activationId), nodeId)));
        completeIfConverged(active, activation, mapper.lockTargets(activationId), now);
        return mapper.selectActivation(activationId);
    }

    @Transactional
    public ReleaseActivationRecord waive(
            String activationId,
            String nodeId,
            String reason,
            OperatorContext operator) {
        ReleaseActivationRecord hint = mapper.selectActivation(activationId);
        if (hint == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Activation not found");
        ActiveReleaseRecord active = mapper.lockActive(hint.environment(), hint.appCode());
        ReleaseActivationRecord activation = mapper.lockActivation(activationId);
        List<ActivationTargetRecord> targets = mapper.lockTargets(activationId);
        ActivationTargetRecord before = target(targets, nodeId);
        if (mapper.waiveTarget(activationId, nodeId, operator.operatorId(), reason, clock.instant()) != 1) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "Only WAITING/FAILED required targets can be waived");
        }
        List<ActivationTargetRecord> updatedTargets = mapper.lockTargets(activationId);
        auditService.record(operator, "RELEASE_TARGET_WAIVE", "ACTIVATION_TARGET", before.id(), null,
                auditTarget(before), auditTarget(target(updatedTargets, nodeId)));
        completeIfConverged(active, activation, updatedTargets, clock.instant());
        return mapper.selectActivation(activationId);
    }

    private void completeIfConverged(
            ActiveReleaseRecord active,
            ReleaseActivationRecord activation,
            List<ActivationTargetRecord> targets,
            Instant now) {
        if ("PENDING".equals(activation.status()) || "APPLIED".equals(activation.status())) return;
        boolean converged = targets.stream().allMatch(target -> target.required()
                ? "READY".equals(target.status())
                : Set.of("LEFT", "WAIVED").contains(target.status()));
        if (!converged) return;
        mapper.updateActivationStatus(activation.id(), "APPLIED", now);
        mapper.updateActiveState(
                activation.environment(), activation.appCode(), activation.toReleaseId(),
                activation.toActivationVersion(), "APPLIED", now);
        mapper.markReleasePublished(activation.toReleaseId(), activation.operator(), now);
        mapper.publishScenarioItems(activation.toReleaseId(), now);
        OperatorContext system = systemOperator(activation, "applied");
        auditService.record(system, "RELEASE_ACTIVATION_APPLIED", "RELEASE_ACTIVATION", activation.id(),
                null, auditActivation(activation), auditActivation(mapper.selectActivation(activation.id())));
    }

    private ReleaseRecord requireRelease(String id) {
        ReleaseRecord release = mapper.selectRelease(id);
        if (release == null) throw new PlatformException(ErrorCode.NOT_FOUND, "Release not found");
        return release;
    }

    private List<RuntimeNodeDiscoveryPort.RuntimeNode> normalizeNodes(
            List<RuntimeNodeDiscoveryPort.RuntimeNode> nodes) {
        if (nodes == null) return List.of();
        LinkedHashMap<String, RuntimeNodeDiscoveryPort.RuntimeNode> unique = new LinkedHashMap<>();
        for (RuntimeNodeDiscoveryPort.RuntimeNode node : nodes) {
            if (node == null || node.nodeId() == null || node.nodeId().isBlank() || node.nodeId().length() > 128) {
                throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                        "Service discovery returned an invalid Runtime node");
            }
            if (unique.putIfAbsent(node.nodeId(), node) != null) {
                throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE,
                        "Service discovery returned duplicate Runtime nodes");
            }
        }
        return List.copyOf(unique.values());
    }

    private ActivationTargetRecord target(List<ActivationTargetRecord> targets, String nodeId) {
        return targets.stream().filter(item -> item.runtimeNodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new PlatformException(ErrorCode.NOT_FOUND, "Activation target not found"));
    }

    private OperatorContext systemOperator(ReleaseActivationRecord activation, String suffix) {
        String requestId = activation.requestId() + "-" + suffix;
        if (requestId.length() > 64) requestId = requestId.substring(0, 64);
        return new OperatorContext(activation.operator(), Set.of("SYSTEM"), requestId);
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return "unspecified failure";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(512, normalized.length()));
    }

    private Map<String, Object> auditRelease(ReleaseRecord release) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", release.id());
        value.put("releaseCode", release.releaseCode());
        value.put("environment", release.environment());
        value.put("app", release.appCode());
        value.put("status", release.status());
        value.put("checksum", release.checksum());
        return value;
    }

    private Map<String, Object> auditActivation(ReleaseActivationRecord activation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", activation.id());
        value.put("environment", activation.environment());
        value.put("app", activation.appCode());
        value.put("fromReleaseId", activation.fromReleaseId());
        value.put("toReleaseId", activation.toReleaseId());
        value.put("activationVersion", activation.toActivationVersion());
        value.put("action", activation.action());
        value.put("status", activation.status());
        return value;
    }

    private Map<String, Object> auditTarget(ActivationTargetRecord target) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", target.id());
        value.put("activationId", target.activationId());
        value.put("runtimeNodeId", target.runtimeNodeId());
        value.put("required", target.required());
        value.put("status", target.status());
        value.put("waivedBy", target.waivedBy());
        value.put("waiveReason", target.waiveReason());
        return value;
    }

    public record ReleasePointer(
            String releaseId,
            long activationVersion,
            String snapshotChecksum,
            String signatureKeyId) {
    }

    public record AckCommand(
            String environment,
            String app,
            String nodeId,
            String releaseId,
            long activationVersion,
            String status,
            String errorMasked) {
    }

    public record AckResult(String activationId, String activationStatus, long activationVersion) {
    }
}
