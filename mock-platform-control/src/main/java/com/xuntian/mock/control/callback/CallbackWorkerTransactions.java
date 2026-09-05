package com.xuntian.mock.control.callback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CallbackWorkerTransactions {

    private static final TypeReference<List<Long>> LONGS = new TypeReference<>() { };
    private static final Duration LEASE = Duration.ofSeconds(30);
    private final CallbackMapper mapper;
    private final ObjectMapper objectMapper;

    public CallbackWorkerTransactions(CallbackMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper.copy();
    }

    @Transactional
    public List<ClaimedTask> claim(String worker, Instant now, int limit) {
        List<ClaimedTask> result = new ArrayList<>();
        for (CallbackTaskRecord task : mapper.lockClaimable(now, limit)) {
            if ("RUNNING".equals(task.status())) {
                CallbackAttemptRecord latest = mapper.selectLatestAttempt(task.taskId());
                if (latest != null && "STARTED".equals(latest.status())) {
                    mapper.abandonStarted(task.taskId(), task.fencingToken(), now);
                    if (sendBudgetExhausted(task)) {
                        mapper.exhaustExpiredStarted(task.taskId(), task.fencingToken());
                        continue;
                    }
                } else if (latest != null && "PREPARING".equals(latest.status())) {
                    mapper.abandonPreparation(task.taskId(), task.fencingToken(), now);
                    if (preparationBudgetExhausted(task)) {
                        mapper.failExpiredRunning(task.taskId(), task.fencingToken(),
                                "FAILED_PREPARATION", "Worker lease expired during preparation");
                        continue;
                    }
                    mapper.incrementExpiredPreparation(task.taskId(), task.fencingToken());
                }
            } else if (sendBudgetExhausted(task)) {
                mapper.failPending(task.taskId(), "FAILED", "Callback send budget is exhausted");
                continue;
            }
            if (mapper.claim(task.taskId(), worker, now.plus(LEASE), task.fencingToken(), now) != 1) continue;
            long fence = task.fencingToken() + 1;
            int attemptNo = mapper.nextAttemptNo(task.taskId());
            mapper.insertPreparingAttempt(task.taskId(), task.deliveryId(), attemptNo, fence, now);
            result.add(new ClaimedTask(task, worker, fence, attemptNo));
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public boolean flowAccepts(ClaimedTask claim) {
        CallbackFlowState flow = mapper.selectFlowState(claim.task().flowInstanceId());
        return flow != null && flow.accepts(claim.task().flowGeneration());
    }

    @Transactional
    public boolean start(ClaimedTask claim, boolean flowAccepts, Instant now) {
        CallbackTaskRecord task = mapper.lockOwned(
                claim.task().taskId(), claim.worker(), claim.fencingToken());
        if (task == null || task.leaseUntil() == null || !task.leaseUntil().isAfter(now)) return false;
        if (!flowAccepts) {
            requireOne(mapper.cancelPreparingAttempt(
                    task.taskId(), claim.fencingToken(), now,
                    "FLOW_GENERATION_INVALID", "Flow was reset, deleted or expired"));
            requireOne(mapper.finishOwnedTask(
                    task.taskId(), claim.worker(), claim.fencingToken(), "CANCELLED",
                    null, 0, null, "Flow was reset, deleted or expired"));
            return false;
        }
        if (sendBudgetExhausted(task)) {
            requireOne(mapper.cancelPreparingAttempt(
                    task.taskId(), claim.fencingToken(), now,
                    "SEND_BUDGET_EXHAUSTED", "Callback send budget is exhausted"));
            requireOne(mapper.finishOwnedTask(
                    task.taskId(), claim.worker(), claim.fencingToken(), "FAILED",
                    null, 0, null, "Callback send budget is exhausted"));
            return false;
        }
        int sendAttemptNo = task.sendAttemptCount() + 1;
        requireOne(mapper.startAttempt(task.taskId(), claim.fencingToken(), sendAttemptNo));
        requireOne(mapper.markSendStarted(
                task.taskId(), claim.worker(), claim.fencingToken(), now.plus(LEASE)));
        return true;
    }

    @Transactional
    public void preparationFailed(ClaimedTask claim, String maskedError, Instant now) {
        CallbackTaskRecord task = mapper.lockOwned(
                claim.task().taskId(), claim.worker(), claim.fencingToken());
        if (task == null) return;
        requireOne(mapper.failPreparingAttempt(task.taskId(), claim.fencingToken(), now, maskedError));
        boolean exhausted = preparationBudgetExhausted(task);
        requireOne(mapper.finishOwnedTask(
                task.taskId(), claim.worker(), claim.fencingToken(),
                exhausted ? "FAILED_PREPARATION" : "RETRYING",
                exhausted ? null : now.plus(preparationBackoff(task.preparationRetryCount() + 1)),
                1, null, maskedError));
    }

    @Transactional
    public void finalizeSend(
            ClaimedTask claim,
            CallbackDispatcher.SendOutcome outcome,
            Instant now) {
        CallbackTaskRecord task = mapper.lockOwned(
                claim.task().taskId(), claim.worker(), claim.fencingToken());
        if (task == null) return;
        String attemptStatus = outcome.success() ? "SUCCESS" : "FAILED";
        requireOne(mapper.completeStartedAttempt(
                task.taskId(), claim.fencingToken(), attemptStatus, now,
                outcome.httpStatus(), outcome.result(), outcome.certainty(),
                outcome.errorMasked(), outcome.durationMs()));
        if (outcome.success()) {
            requireOne(mapper.finishOwnedTask(
                    task.taskId(), claim.worker(), claim.fencingToken(), "SUCCESS",
                    null, 0, outcome.httpStatus(), null));
            return;
        }
        boolean exhausted = sendBudgetExhausted(task);
        String finalStatus = exhausted
                ? ("UNKNOWN".equals(outcome.certainty()) ? "FAILED_UNCONFIRMED" : "FAILED")
                : "RETRYING";
        requireOne(mapper.finishOwnedTask(
                task.taskId(), claim.worker(), claim.fencingToken(), finalStatus,
                exhausted ? null : now.plus(sendBackoff(task)), 0,
                outcome.httpStatus(), outcome.errorMasked()));
    }

    private Duration sendBackoff(CallbackTaskRecord task) {
        List<Long> intervals;
        try { intervals = objectMapper.readValue(task.retryIntervalsJson(), LONGS); }
        catch (JsonProcessingException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Stored Callback retry schedule is invalid", failure);
        }
        if (intervals.isEmpty()) return Duration.ZERO;
        int retryIndex = Math.max(0, Math.min(task.sendAttemptCount() - 1, intervals.size() - 1));
        return Duration.ofMillis(intervals.get(retryIndex));
    }

    private static Duration preparationBackoff(int failureCount) {
        return Duration.ofSeconds(Math.min(60, 1L << Math.min(5, Math.max(0, failureCount - 1))));
    }
    private static boolean sendBudgetExhausted(CallbackTaskRecord task) {
        return task.sendAttemptCount() >= 1 + task.maxRetry() + task.manualSendGrantCount();
    }
    private static boolean preparationBudgetExhausted(CallbackTaskRecord task) {
        return task.preparationRetryCount() >= task.maxPreparationRetry()
                + task.manualPreparationGrantCount();
    }
    private static void requireOne(int updated) {
        if (updated != 1) throw new PlatformException(ErrorCode.CONFLICT, "Callback fencing check failed");
    }

    public record ClaimedTask(
            CallbackTaskRecord task,
            String worker,
            long fencingToken,
            int attemptNo) { }
}
