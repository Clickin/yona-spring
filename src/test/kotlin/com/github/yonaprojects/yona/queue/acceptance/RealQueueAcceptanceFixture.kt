package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.queue.Queue
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

data class QueueJobRow(
    val id: Long,
    val status: String,
    val payloadVersion: Int,
    val executionGeneration: Long,
    val generationAttemptNo: Int,
    val attemptCount: Long,
)

data class QueueAttemptRow(
    val attemptNo: Long,
    val executionGeneration: Long,
    val generationAttemptNo: Int,
    val status: String,
    val fence: Long,
)

/** PR01 subset of the independently frozen seam; worker-only observations activate in PR02. */
interface RealQueueAcceptanceFixture : AutoCloseable {
    val dataSource: DataSource
    val transactionManager: PlatformTransactionManager
    val queue: Queue
    fun createAcceptanceMarkerTable()
    fun seedExistingYonaRowsThroughProductionServices()
    fun registerStoreAcceptanceTasks()
    fun jobRow(jobId: Long): QueueJobRow?
    fun attemptRows(jobId: Long): List<QueueAttemptRow>
    fun idempotencyRowCount(jobId: Long): Int
    fun snapshotUnrelatedYonaRows(): Map<String, String>
    fun applyQueueOnlyUpgradeToExistingYonaSchema()
    fun reopenOnSamePersistentDatabase(): RealQueueAcceptanceFixture
}
