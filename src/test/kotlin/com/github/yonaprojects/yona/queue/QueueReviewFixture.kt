package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import org.springframework.transaction.support.TransactionTemplate

/** The schema fixture is shared across methods; leave unrelated acceptance scenarios intact. */
internal fun <T> withQueueReviewFixture(block: (JdbcQueueAcceptanceFixture) -> T): T =
    JdbcQueueAcceptanceFixture().use { fixture ->
        try { block(fixture) } finally { removeQueueReviewJobs(fixture) }
    }

internal fun removeQueueReviewJobs(fixture: JdbcQueueAcceptanceFixture) {
    TransactionTemplate(fixture.transactionManager).executeWithoutResult {
        val jobs = "SELECT id FROM queue_job WHERE task_type LIKE 'queue.review.%'"
        val failures = fixture.jdbc.queryForObject(
            "SELECT COUNT(*) FROM queue_job WHERE task_type LIKE 'queue.review.%' AND status = 'FAILED'",
            Long::class.javaObjectType,
        )!!
        fixture.jdbc.update(
            "UPDATE queue_resource_lock SET current_job_id = NULL, current_attempt_no = NULL, " +
                "current_fence = NULL, lease_expires_at_epoch_ms = NULL WHERE current_job_id IN ($jobs)",
        )
        for (table in listOf("queue_artifact", "queue_admin_audit", "queue_idempotency_key", "queue_job_resource", "queue_attempt")) {
            fixture.jdbc.update("DELETE FROM $table WHERE job_id IN ($jobs)")
        }
        fixture.jdbc.update("DELETE FROM queue_job WHERE task_type LIKE 'queue.review.%'")
        if (failures > 0) {
            check(fixture.jdbc.update(
                "UPDATE queue_meta SET counter_value = counter_value - ? WHERE counter_name = 'failed-jobs' AND counter_value >= ?",
                failures, failures,
            ) == 1)
        }
    }
}
