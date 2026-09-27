package com.github.yonaprojects.yona.queue

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
internal class QueueMetrics(
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    private val clock: QueueClock,
    registry: MeterRegistry,
) {
    private val transactions = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private data class Snapshot(val jobs: DoubleArray, val oldestDueAge: Double)
    @Volatile private var snapshot = unavailable()

    init {
        (PENDING + QueueStatus.FAILED).forEach { status ->
            Gauge.builder("yona.queue.jobs", this) { it.snapshot.jobs[status.ordinal] }.tag("status", status.name).register(registry)
        }
        Gauge.builder("yona.queue.oldest.due.age", this) { it.snapshot.oldestDueAge }.baseUnit("seconds").register(registry)
    }

    // Admission bounds these live rows; retained history is never rescanned by the sampler.
    @Scheduled(initialDelayString = "\${yona.queue.metrics-refresh-millis:30000}", fixedDelayString = "\${yona.queue.metrics-refresh-millis:30000}")
    fun refresh() {
        snapshot = try {
            transactions.execute {
                val now = clock.now()
                val jobs = DoubleArray(QueueStatus.entries.size)
                var oldest: Long? = null
                val rows = entityManager.createQuery(
                    "select j.status as status, count(j.id) as total, min(case when j.status = :retry then j.nextAttemptAt else j.scheduledAt end) as due from QueueJob j where j.status in :pending group by j.status",
                    Tuple::class.java,
                ).setParameter("retry", QueueStatus.RETRY_WAIT).setParameter("pending", PENDING).setMaxResults(PENDING.size).resultList
                for (row in rows) {
                    val status = row.get("status") as QueueStatus
                    jobs[status.ordinal] = (row.get("total") as Number).toDouble()
                    if (status == QueueStatus.QUEUED || status == QueueStatus.RETRY_WAIT) {
                        val due = (row.get("due") as Number?)?.toLong()
                        if (due != null && due <= now && (oldest == null || due < oldest)) oldest = due
                    }
                }
                jobs[QueueStatus.FAILED.ordinal] = entityManager.createQuery(
                    "select c.value from QueueCounter c where c.name = :name", Long::class.javaObjectType,
                ).setParameter("name", FAILED_COUNTER).singleResult.toDouble()
                Snapshot(jobs, oldest?.let { (now - it).toDouble() / 1000.0 } ?: 0.0)
            }!!
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun unavailable() = Snapshot(DoubleArray(QueueStatus.entries.size) { Double.NaN }, Double.NaN)

    companion object {
        internal const val FAILED_COUNTER = "failed-jobs"
        private val PENDING = listOf(QueueStatus.QUEUED, QueueStatus.RUNNING, QueueStatus.RETRY_WAIT, QueueStatus.CANCEL_REQUESTED)

        internal fun transition(entityManager: EntityManager, before: QueueStatus, after: QueueStatus) {
            val delta = (if (after == QueueStatus.FAILED) 1 else 0) - (if (before == QueueStatus.FAILED) 1 else 0)
            if (delta == 0) return
            val changed = entityManager.createQuery(
                "update QueueCounter c set c.value = c.value + :delta where c.name = :name and c.value >= :minimum",
            ).setParameter("name", FAILED_COUNTER).setParameter("delta", delta.toLong())
                .setParameter("minimum", if (delta < 0) 1L else 0L).executeUpdate()
            check(changed == 1) { "Queue failed-job counter is unavailable" }
        }
    }
}
