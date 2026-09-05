package com.xuntian.mock.control.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallbackWorkerTransactionsTest {

    private final CallbackMapper mapper = mock(CallbackMapper.class);
    private final CallbackWorkerTransactions transactions = new CallbackWorkerTransactions(
            mapper, new ObjectMapper().findAndRegisterModules());
    private final Instant now = Instant.parse("2026-08-31T08:00:00Z");

    @Test
    void expiredStartedAttemptAtBudgetLimitBecomesFailedUnconfirmedWithoutNewAttempt() {
        CallbackTaskRecord task = task("RUNNING", 1, 0, 0, 3, 4, now.minusSeconds(1));
        when(mapper.lockClaimable(now, 20)).thenReturn(List.of(task));
        when(mapper.selectLatestAttempt(task.taskId())).thenReturn(attempt("STARTED", 4));
        when(mapper.abandonStarted(task.taskId(), 4, now)).thenReturn(1);
        when(mapper.exhaustExpiredStarted(task.taskId(), 4)).thenReturn(1);

        assertThat(transactions.claim("worker-1", now, 20)).isEmpty();

        verify(mapper).exhaustExpiredStarted(task.taskId(), 4);
        verify(mapper, never()).claim(any(), any(), any(), eq(4L), any());
        verify(mapper, never()).insertPreparingAttempt(any(), any(), any(Integer.class), any(Long.class), any());
    }

    @Test
    void unknownDeliveryAtBudgetLimitFinalizesAsFailedUnconfirmed() {
        CallbackTaskRecord task = task("RUNNING", 1, 0, 0, 3, 5, now.plusSeconds(20));
        CallbackWorkerTransactions.ClaimedTask claim = new CallbackWorkerTransactions.ClaimedTask(
                task, "worker-1", 5, 1);
        when(mapper.lockOwned(task.taskId(), "worker-1", 5)).thenReturn(task);
        when(mapper.completeStartedAttempt(
                task.taskId(), 5, "FAILED", now, null,
                "TRANSPORT_UNKNOWN", "UNKNOWN", "IOException", 12)).thenReturn(1);
        when(mapper.finishOwnedTask(
                task.taskId(), "worker-1", 5, "FAILED_UNCONFIRMED",
                null, 0, null, "IOException")).thenReturn(1);

        transactions.finalizeSend(
                claim, CallbackDispatcher.SendOutcome.unknown("IOException", 12), now);

        verify(mapper).finishOwnedTask(
                task.taskId(), "worker-1", 5, "FAILED_UNCONFIRMED",
                null, 0, null, "IOException");
    }

    @Test
    void confirmedFailureUsesFixedRetrySchedule() {
        CallbackTaskRecord task = task("RUNNING", 1, 2, 0, 3, 5, now.plusSeconds(20));
        CallbackWorkerTransactions.ClaimedTask claim = new CallbackWorkerTransactions.ClaimedTask(
                task, "worker-1", 5, 1);
        when(mapper.lockOwned(task.taskId(), "worker-1", 5)).thenReturn(task);
        when(mapper.completeStartedAttempt(
                task.taskId(), 5, "FAILED", now, 503,
                "HTTP_ERROR", "CONFIRMED_RESPONSE", "Callback returned HTTP 503", 8)).thenReturn(1);
        when(mapper.finishOwnedTask(
                task.taskId(), "worker-1", 5, "RETRYING", now.plusMillis(1000),
                0, 503, "Callback returned HTTP 503")).thenReturn(1);

        transactions.finalizeSend(
                claim, CallbackDispatcher.SendOutcome.confirmed(503, 8), now);

        verify(mapper).finishOwnedTask(
                task.taskId(), "worker-1", 5, "RETRYING", now.plusMillis(1000),
                0, 503, "Callback returned HTTP 503");
    }

    @Test
    void preparationFailureRetriesWithoutConsumingSendBudget() {
        CallbackTaskRecord task = task("RUNNING", 0, 2, 0, 3, 5, now.plusSeconds(20));
        CallbackWorkerTransactions.ClaimedTask claim = new CallbackWorkerTransactions.ClaimedTask(
                task, "worker-1", 5, 1);
        when(mapper.lockOwned(task.taskId(), "worker-1", 5)).thenReturn(task);
        when(mapper.failPreparingAttempt(task.taskId(), 5, now, "UnknownHostException")).thenReturn(1);
        when(mapper.finishOwnedTask(
                task.taskId(), "worker-1", 5, "RETRYING", now.plusSeconds(1),
                1, null, "UnknownHostException")).thenReturn(1);

        transactions.preparationFailed(claim, "UnknownHostException", now);

        verify(mapper).finishOwnedTask(
                task.taskId(), "worker-1", 5, "RETRYING", now.plusSeconds(1),
                1, null, "UnknownHostException");
        verify(mapper, never()).markSendStarted(any(), any(), any(Long.class), any());
    }

    @Test
    void staleFencingOwnerCannotFinalize() {
        CallbackTaskRecord task = task("RUNNING", 1, 2, 0, 3, 5, now.plusSeconds(20));
        CallbackWorkerTransactions.ClaimedTask claim = new CallbackWorkerTransactions.ClaimedTask(
                task, "old-worker", 4, 1);
        when(mapper.lockOwned(task.taskId(), "old-worker", 4)).thenReturn(null);

        transactions.finalizeSend(claim, CallbackDispatcher.SendOutcome.confirmed(200, 3), now);

        verify(mapper, never()).completeStartedAttempt(
                any(), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class));
        verify(mapper, never()).finishOwnedTask(
                any(), any(), any(Long.class), any(), any(), any(Integer.class), any(), any());
    }

    private CallbackTaskRecord task(
            String status,
            int sendCount,
            int maxRetry,
            int preparationCount,
            int maxPreparationRetry,
            long fence,
            Instant leaseUntil) {
        return new CallbackTaskRecord(
                1, "task-1", "delivery-1", 9, 7, 1, "release-1", "a".repeat(64),
                "callback-1", 0, "OA", "oa.query", new byte[]{1}, "POST",
                new byte[]{2}, new byte[]{3}, "b".repeat(64), "key-1", 11, 12,
                status, now, sendCount, maxRetry, "[1000,2000]", preparationCount,
                maxPreparationRetry, 0, 0, "worker-1", leaseUntil, fence,
                null, null, now.plusSeconds(3600), now.minusSeconds(10), now);
    }

    private CallbackAttemptRecord attempt(String status, long fence) {
        return new CallbackAttemptRecord(
                1, "task-1", "delivery-1", 1, 1, fence, status,
                now.minusSeconds(30), null, null, null, null, null, null);
    }
}
