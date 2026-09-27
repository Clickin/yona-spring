package com.github.yonaprojects.yona.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Expected values below are the independent normative table, not values read
 * back from the reducer under test. QueueTransition.decide is pure: no DB,
 * clock, worker, HTTP, retry scheduler, or test-only storage is called here.
 */
class QueueTransitionAcceptanceTest {
    companion object {
        private fun snapshot(
            status: QueueStatus,
            generation: Long = 1,
            generationAttempt: Int = 0,
            totalAttempts: Long = 0,
            disposition: FailureDisposition? = null,
            activeOutcome: AttemptOutcome? = null,
        ) = QueueTransitionState(status, generation, generationAttempt, totalAttempts, disposition, activeOutcome)

        private fun policy(
            maxAttempts: Int = 5,
            registered: Boolean = true,
            replaySafe: Boolean = true,
        ) = QueueTransitionPolicy(maxGenerationAttempts = maxAttempts, handlerRegistered = registered, replaySafe = replaySafe)

        private fun result(
            accepted: Boolean,
            state: QueueStatus,
            generation: Long,
            generationAttempt: Int,
            totalAttempts: Long,
            disposition: FailureDisposition? = null,
            activeOutcome: AttemptOutcome? = null,
            errorCode: String? = null,
            audit: Boolean = false,
        ) = QueueTransitionDecision(accepted, errorCode, state, disposition, generation,
                                    generationAttempt, totalAttempts, activeOutcome, audit)

        @JvmStatic
        fun transitionCases(): Stream<Arguments> = Stream.of(
            Arguments.of("due claim appends first attempt",
                QueueTransitionInput(snapshot(QueueStatus.QUEUED), QueueTransitionEvent.ClaimDue(due = true, resourcesAcquired = true), policy()),
                result(true, QueueStatus.RUNNING, 1, 1, 1, activeOutcome = AttemptOutcome.RUNNING)),
            Arguments.of("not-due claim is a no-op",
                QueueTransitionInput(snapshot(QueueStatus.QUEUED), QueueTransitionEvent.ClaimDue(due = false, resourcesAcquired = true), policy()),
                result(false, QueueStatus.QUEUED, 1, 0, 0, errorCode = "NOT_DUE")),
            Arguments.of("future unsupported job stays queued until due",
                QueueTransitionInput(snapshot(QueueStatus.QUEUED), QueueTransitionEvent.ClaimDue(due = false, resourcesAcquired = true), policy(registered = false)),
                result(false, QueueStatus.QUEUED, 1, 0, 0, errorCode = "NOT_DUE")),
            Arguments.of("resource contention rejects claim without changing counters",
                QueueTransitionInput(snapshot(QueueStatus.QUEUED), QueueTransitionEvent.ClaimDue(due = true, resourcesAcquired = false), policy()),
                result(false, QueueStatus.QUEUED, 1, 0, 0, errorCode = "INVALID_TRANSITION")),
            Arguments.of("pending cancel has no attempt",
                QueueTransitionInput(snapshot(QueueStatus.QUEUED), QueueTransitionEvent.Cancel, policy()),
                result(true, QueueStatus.CANCELLED, 1, 0, 0, audit = true)),
            Arguments.of("running cancel only requests stop",
                QueueTransitionInput(snapshot(QueueStatus.RUNNING, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.Cancel, policy()),
                result(true, QueueStatus.CANCEL_REQUESTED, 1, 1, 1, activeOutcome = AttemptOutcome.RUNNING, audit = true)),
            Arguments.of("completion wins if handler returns before observing cancel",
                QueueTransitionInput(snapshot(QueueStatus.CANCEL_REQUESTED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.HandlerSucceeded, policy()),
                result(true, QueueStatus.SUCCEEDED, 1, 1, 1, activeOutcome = AttemptOutcome.SUCCEEDED)),
            Arguments.of("cooperative stop completes cancellation",
                QueueTransitionInput(snapshot(QueueStatus.CANCEL_REQUESTED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.CooperativeCancelCompleted, policy()),
                result(true, QueueStatus.CANCELLED, 1, 1, 1, activeOutcome = AttemptOutcome.CANCELLED)),
            Arguments.of("retryable failure after cancellation preserves outcome without retry",
                QueueTransitionInput(snapshot(QueueStatus.CANCEL_REQUESTED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.RetryableFailure, policy()),
                result(true, QueueStatus.CANCELLED, 1, 1, 1, activeOutcome = AttemptOutcome.RETRYABLE_FAILURE)),
            Arguments.of("permanent failure after cancellation preserves outcome",
                QueueTransitionInput(snapshot(QueueStatus.CANCEL_REQUESTED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.PermanentFailure, policy()),
                result(true, QueueStatus.CANCELLED, 1, 1, 1, activeOutcome = AttemptOutcome.PERMANENT_FAILURE)),
            Arguments.of("unclassified failure after cancellation preserves outcome",
                QueueTransitionInput(snapshot(QueueStatus.CANCEL_REQUESTED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.UnclassifiedFailure, policy()),
                result(true, QueueStatus.CANCELLED, 1, 1, 1, activeOutcome = AttemptOutcome.RECOVERY_REQUIRED)),
            Arguments.of("retryable failure schedules within generation budget",
                QueueTransitionInput(snapshot(QueueStatus.RUNNING, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.RetryableFailure, policy()),
                result(true, QueueStatus.RETRY_WAIT, 1, 1, 1, activeOutcome = AttemptOutcome.RETRYABLE_FAILURE)),
            Arguments.of("retry exhaustion is terminal and classified",
                QueueTransitionInput(snapshot(QueueStatus.RUNNING, generationAttempt = 5, totalAttempts = 5, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.RetryableFailure, policy()),
                result(true, QueueStatus.FAILED, 1, 5, 5, FailureDisposition.RETRY_EXHAUSTED, AttemptOutcome.RETRYABLE_FAILURE)),
            Arguments.of("permanent failure never retries automatically",
                QueueTransitionInput(snapshot(QueueStatus.RUNNING, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.PermanentFailure, policy()),
                result(true, QueueStatus.FAILED, 1, 1, 1, FailureDisposition.PERMANENT, AttemptOutcome.PERMANENT_FAILURE)),
            Arguments.of("unclassified exception requires recovery",
                QueueTransitionInput(snapshot(QueueStatus.RUNNING, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.UnclassifiedFailure, policy()),
                result(true, QueueStatus.RECOVERY_REQUIRED, 1, 1, 1, activeOutcome = AttemptOutcome.RECOVERY_REQUIRED)),
            Arguments.of("safe lease expiry records lease loss and schedules replay",
                QueueTransitionInput(snapshot(QueueStatus.RUNNING, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.LeaseExpired, policy(replaySafe = true)),
                result(true, QueueStatus.RETRY_WAIT, 1, 1, 1, activeOutcome = AttemptOutcome.LEASE_LOST)),
            Arguments.of("unsafe lease expiry requires recovery",
                QueueTransitionInput(snapshot(QueueStatus.RUNNING, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.LeaseExpired, policy(replaySafe = false)),
                result(true, QueueStatus.RECOVERY_REQUIRED, 1, 1, 1, activeOutcome = AttemptOutcome.LEASE_LOST)),
            Arguments.of("cancel-requested lease expiry honors the cancellation and records lease loss",
                QueueTransitionInput(snapshot(QueueStatus.CANCEL_REQUESTED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.LeaseExpired, policy(replaySafe = true)),
                result(true, QueueStatus.CANCELLED, 1, 1, 1, activeOutcome = AttemptOutcome.LEASE_LOST)),
            Arguments.of("cancel-requested lease expiry is cancelled even for replay-unsafe tasks",
                QueueTransitionInput(snapshot(QueueStatus.CANCEL_REQUESTED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.LeaseExpired, policy(replaySafe = false)),
                result(true, QueueStatus.CANCELLED, 1, 1, 1, activeOutcome = AttemptOutcome.LEASE_LOST)),
            Arguments.of("manual retry starts a new generation without erasing attempts",
                QueueTransitionInput(snapshot(QueueStatus.FAILED, generation = 4, generationAttempt = 5, totalAttempts = 17, disposition = FailureDisposition.PERMANENT), QueueTransitionEvent.ManualRetry(recoveryAcknowledged = false), policy()),
                result(true, QueueStatus.QUEUED, 5, 0, 17, audit = true)),
            Arguments.of("recovery retry requires explicit acknowledgement",
                QueueTransitionInput(snapshot(QueueStatus.RECOVERY_REQUIRED, generation = 4, generationAttempt = 1, totalAttempts = 9), QueueTransitionEvent.ManualRetry(recoveryAcknowledged = false), policy()),
                result(false, QueueStatus.RECOVERY_REQUIRED, 4, 1, 9, errorCode = "RECOVERY_ACK_REQUIRED")),
            Arguments.of("acknowledged recovery retry preserves old attempt count",
                QueueTransitionInput(snapshot(QueueStatus.RECOVERY_REQUIRED, generation = 4, generationAttempt = 1, totalAttempts = 9), QueueTransitionEvent.ManualRetry(recoveryAcknowledged = true), policy()),
                result(true, QueueStatus.QUEUED, 5, 0, 9, audit = true)),
            Arguments.of("unsupported payload cannot be manually run before exact handler registration",
                QueueTransitionInput(snapshot(QueueStatus.BLOCKED_UNSUPPORTED), QueueTransitionEvent.ManualRetry(recoveryAcknowledged = false), policy(registered = false)),
                result(false, QueueStatus.BLOCKED_UNSUPPORTED, 1, 0, 0, errorCode = "UNSUPPORTED_HANDLER")),
            Arguments.of("newly registered exact handler permits audited unsupported recovery",
                QueueTransitionInput(snapshot(QueueStatus.BLOCKED_UNSUPPORTED), QueueTransitionEvent.ManualRetry(recoveryAcknowledged = true), policy(registered = true)),
                result(true, QueueStatus.QUEUED, 2, 0, 0, audit = true)),
            Arguments.of("terminal cancel is rejected without mutation",
                QueueTransitionInput(snapshot(QueueStatus.SUCCEEDED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.SUCCEEDED), QueueTransitionEvent.Cancel, policy()),
                result(false, QueueStatus.SUCCEEDED, 1, 1, 1, activeOutcome = AttemptOutcome.SUCCEEDED, errorCode = "INVALID_TRANSITION")),
            Arguments.of("terminal retry is rejected without mutation",
                QueueTransitionInput(snapshot(QueueStatus.SUCCEEDED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.SUCCEEDED), QueueTransitionEvent.ManualRetry(recoveryAcknowledged = false), policy()),
                result(false, QueueStatus.SUCCEEDED, 1, 1, 1, activeOutcome = AttemptOutcome.SUCCEEDED, errorCode = "INVALID_TRANSITION")),
            Arguments.of("failed job may be abandoned without erasing attempt history",
                QueueTransitionInput(snapshot(QueueStatus.FAILED, generation = 3, generationAttempt = 2, totalAttempts = 8, disposition = FailureDisposition.PERMANENT, activeOutcome = AttemptOutcome.PERMANENT_FAILURE), QueueTransitionEvent.Abandon(false), policy()),
                result(true, QueueStatus.CANCELLED, 3, 2, 8, activeOutcome = AttemptOutcome.PERMANENT_FAILURE, audit = true)),
            Arguments.of("unsupported job may be abandoned without a registered handler",
                QueueTransitionInput(snapshot(QueueStatus.BLOCKED_UNSUPPORTED), QueueTransitionEvent.Abandon(false), policy(registered = false)),
                result(true, QueueStatus.CANCELLED, 1, 0, 0, audit = true)),
            Arguments.of("recovery abandon requires acknowledgement",
                QueueTransitionInput(snapshot(QueueStatus.RECOVERY_REQUIRED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.LEASE_LOST), QueueTransitionEvent.Abandon(false), policy()),
                result(false, QueueStatus.RECOVERY_REQUIRED, 1, 1, 1, activeOutcome = AttemptOutcome.LEASE_LOST, errorCode = "RECOVERY_ACK_REQUIRED")),
            Arguments.of("acknowledged recovery abandon preserves lease-loss history",
                QueueTransitionInput(snapshot(QueueStatus.RECOVERY_REQUIRED, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.LEASE_LOST), QueueTransitionEvent.Abandon(true), policy()),
                result(true, QueueStatus.CANCELLED, 1, 1, 1, activeOutcome = AttemptOutcome.LEASE_LOST, audit = true)),
            Arguments.of("active execution cannot be abandoned",
                QueueTransitionInput(snapshot(QueueStatus.RUNNING, generationAttempt = 1, totalAttempts = 1, activeOutcome = AttemptOutcome.RUNNING), QueueTransitionEvent.Abandon(true), policy()),
                result(false, QueueStatus.RUNNING, 1, 1, 1, activeOutcome = AttemptOutcome.RUNNING, errorCode = "INVALID_TRANSITION")),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitionCases")
    internal fun appliesFrozenDecisionTable(name: String, input: QueueTransitionInput, expected: QueueTransitionDecision) {
        assertEquals(expected, QueueTransition.decide(input), name)
    }
}
