package com.github.yonaprojects.yona.queue

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@Component
internal class QueueMetrics(
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    private val clock: QueueClock,
    registry: MeterRegistry,
    @Value("\${yona.queue.metrics-refresh-millis:30000}") private val refreshMillis: Long = 30_000,
) : SmartLifecycle {
    private val transactions = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private data class Snapshot(val jobs: DoubleArray, val oldestDueAge: Double)
    @Volatile private var snapshot = unavailable()
    @Volatile private var scheduler: ScheduledExecutorService? = null

    init {
        (PENDING + QueueStatus.FAILED).forEach { status ->
            Gauge.builder("yona.queue.jobs", this) { it.snapshot.jobs[status.ordinal] }.tag("status", status.name).register(registry)
        }
        Gauge.builder("yona.queue.oldest.due.age", this) { it.snapshot.oldestDueAge }.baseUnit("seconds").register(registry)
        Gauge.builder("yona.queue.clock.trusted", clock) { if (it.isTrusted()) 1.0 else 0.0 }.register(registry)
        require(refreshMillis > 0) { "Queue metrics refresh interval must be positive" }
    }

    override fun start() {
        if (scheduler != null) return
        scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "yona-queue-metrics").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::refresh, refreshMillis, refreshMillis, TimeUnit.MILLISECONDS) }
    }

    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning(): Boolean = scheduler != null

    override fun getPhase(): Int = Int.MAX_VALUE - 200

    // Admission bounds these live rows; retained history is never rescanned by the sampler.
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
                ).setParameter("name", Queue.FAILED_COUNTER).singleResult.toDouble()
                Snapshot(jobs, oldest?.let { (now - it).toDouble() / 1000.0 } ?: 0.0)
            }!!
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun unavailable() = Snapshot(DoubleArray(QueueStatus.entries.size) { Double.NaN }, Double.NaN)

    companion object {
        private val PENDING = listOf(QueueStatus.QUEUED, QueueStatus.RUNNING, QueueStatus.RETRY_WAIT, QueueStatus.CANCEL_REQUESTED)
    }
}
