package com.github.yonaprojects.yona.queue

enum class QueueStatus {
    QUEUED, RUNNING, RETRY_WAIT, CANCEL_REQUESTED, SUCCEEDED, FAILED,
    RECOVERY_REQUIRED, BLOCKED_UNSUPPORTED, CANCELLED
}

enum class FailureDisposition { PERMANENT, RETRY_EXHAUSTED }

enum class AttemptOutcome {
    RUNNING, SUCCEEDED, RETRYABLE_FAILURE, PERMANENT_FAILURE, RECOVERY_REQUIRED,
    LEASE_LOST, CANCELLED
}

internal data class QueueTransitionState(
    val status: QueueStatus,
    val executionGeneration: Long,
    val generationAttemptNo: Int,
    val attemptCount: Long,
    val failureDisposition: FailureDisposition? = null,
    val activeOutcome: AttemptOutcome? = null,
)

internal data class QueueTransitionPolicy(
    val maxGenerationAttempts: Int,
    val handlerRegistered: Boolean,
    val replaySafe: Boolean,
)

internal sealed interface QueueTransitionEvent {
    data class ClaimDue(val due: Boolean, val resourcesAcquired: Boolean) : QueueTransitionEvent
    data object Cancel : QueueTransitionEvent
    data object HandlerSucceeded : QueueTransitionEvent
    data object CooperativeCancelCompleted : QueueTransitionEvent
    data object RetryableFailure : QueueTransitionEvent
    data object PermanentFailure : QueueTransitionEvent
    data object UnclassifiedFailure : QueueTransitionEvent
    data object LeaseExpired : QueueTransitionEvent
    data class ManualRetry(val recoveryAcknowledged: Boolean) : QueueTransitionEvent
    data class Abandon(val acknowledged: Boolean) : QueueTransitionEvent
}

internal data class QueueTransitionInput(
    val state: QueueTransitionState,
    val event: QueueTransitionEvent,
    val policy: QueueTransitionPolicy,
)

internal data class QueueTransitionDecision(
    val accepted: Boolean,
    val errorCode: String?,
    val status: QueueStatus,
    val failureDisposition: FailureDisposition?,
    val executionGeneration: Long,
    val generationAttemptNo: Int,
    val attemptCount: Long,
    val activeOutcome: AttemptOutcome?,
    val audit: Boolean,
)

internal object QueueTransition {
    fun decide(input: QueueTransitionInput): QueueTransitionDecision {
        val state = input.state
        val policy = input.policy
        require(policy.maxGenerationAttempts > 0)
        require(state.executionGeneration > 0 && state.generationAttemptNo >= 0 && state.attemptCount >= 0)

        fun reject(code: String = "INVALID_TRANSITION") = QueueTransitionDecision(
            false, code, state.status, state.failureDisposition,
            state.executionGeneration, state.generationAttemptNo, state.attemptCount,
            state.activeOutcome, false,
        )
        fun accept(
            status: QueueStatus,
            outcome: AttemptOutcome? = state.activeOutcome,
            disposition: FailureDisposition? = null,
            generation: Long = state.executionGeneration,
            generationAttempt: Int = state.generationAttemptNo,
            attempts: Long = state.attemptCount,
            audit: Boolean = false,
        ) = QueueTransitionDecision(
            true, null, status, disposition, generation, generationAttempt, attempts, outcome, audit,
        )
        val running = state.status == QueueStatus.RUNNING
        val cancelling = state.status == QueueStatus.CANCEL_REQUESTED
        return when (val event = input.event) {
            is QueueTransitionEvent.ClaimDue -> when {
                state.status != QueueStatus.QUEUED && state.status != QueueStatus.RETRY_WAIT -> reject()
                !event.due -> reject("NOT_DUE")
                !policy.handlerRegistered -> accept(QueueStatus.BLOCKED_UNSUPPORTED)
                !event.resourcesAcquired -> reject()
                state.generationAttemptNo >= policy.maxGenerationAttempts ->
                    accept(QueueStatus.FAILED, disposition = FailureDisposition.RETRY_EXHAUSTED)
                else -> accept(
                    QueueStatus.RUNNING, AttemptOutcome.RUNNING,
                    generationAttempt = Math.addExact(state.generationAttemptNo, 1),
                    attempts = Math.addExact(state.attemptCount, 1),
                )
            }
            QueueTransitionEvent.Cancel -> when (state.status) {
                QueueStatus.QUEUED, QueueStatus.RETRY_WAIT -> accept(QueueStatus.CANCELLED, audit = true)
                QueueStatus.RUNNING -> accept(QueueStatus.CANCEL_REQUESTED, audit = true)
                QueueStatus.CANCEL_REQUESTED, QueueStatus.CANCELLED -> accept(state.status)
                else -> reject()
            }
            QueueTransitionEvent.HandlerSucceeded ->
                if (running || cancelling) accept(QueueStatus.SUCCEEDED, AttemptOutcome.SUCCEEDED) else reject()
            QueueTransitionEvent.CooperativeCancelCompleted ->
                if (cancelling) accept(QueueStatus.CANCELLED, AttemptOutcome.CANCELLED) else reject()
            QueueTransitionEvent.RetryableFailure -> when {
                cancelling -> accept(QueueStatus.CANCELLED, AttemptOutcome.RETRYABLE_FAILURE)
                !running -> reject()
                state.generationAttemptNo >= policy.maxGenerationAttempts ->
                    accept(QueueStatus.FAILED, AttemptOutcome.RETRYABLE_FAILURE, FailureDisposition.RETRY_EXHAUSTED)
                else -> accept(QueueStatus.RETRY_WAIT, AttemptOutcome.RETRYABLE_FAILURE)
            }
            QueueTransitionEvent.PermanentFailure -> when {
                cancelling -> accept(QueueStatus.CANCELLED, AttemptOutcome.PERMANENT_FAILURE)
                running -> accept(QueueStatus.FAILED, AttemptOutcome.PERMANENT_FAILURE, FailureDisposition.PERMANENT)
                else -> reject()
            }
            QueueTransitionEvent.UnclassifiedFailure -> when {
                cancelling -> accept(QueueStatus.CANCELLED, AttemptOutcome.RECOVERY_REQUIRED)
                running -> accept(QueueStatus.RECOVERY_REQUIRED, AttemptOutcome.RECOVERY_REQUIRED)
                else -> reject()
            }
            QueueTransitionEvent.LeaseExpired -> when {
                cancelling -> accept(QueueStatus.CANCELLED, AttemptOutcome.LEASE_LOST)
                !running -> reject()
                !policy.replaySafe -> accept(QueueStatus.RECOVERY_REQUIRED, AttemptOutcome.LEASE_LOST)
                state.generationAttemptNo >= policy.maxGenerationAttempts ->
                    accept(QueueStatus.FAILED, AttemptOutcome.LEASE_LOST, FailureDisposition.RETRY_EXHAUSTED)
                else -> accept(QueueStatus.RETRY_WAIT, AttemptOutcome.LEASE_LOST)
            }
            is QueueTransitionEvent.ManualRetry -> when {
                state.status != QueueStatus.FAILED && state.status != QueueStatus.RECOVERY_REQUIRED &&
                    state.status != QueueStatus.BLOCKED_UNSUPPORTED -> reject()
                !policy.handlerRegistered -> reject("UNSUPPORTED_HANDLER")
                state.status == QueueStatus.RECOVERY_REQUIRED && !event.recoveryAcknowledged ->
                    reject("RECOVERY_ACK_REQUIRED")
                else -> accept(
                    QueueStatus.QUEUED, outcome = null,
                    generation = Math.addExact(state.executionGeneration, 1), generationAttempt = 0, audit = true,
                )
            }
            is QueueTransitionEvent.Abandon -> when {
                state.status != QueueStatus.FAILED && state.status != QueueStatus.RECOVERY_REQUIRED &&
                    state.status != QueueStatus.BLOCKED_UNSUPPORTED -> reject()
                state.status == QueueStatus.RECOVERY_REQUIRED && !event.acknowledged ->
                    reject("RECOVERY_ACK_REQUIRED")
                else -> accept(QueueStatus.CANCELLED, audit = true)
            }
        }
    }
}
