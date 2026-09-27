package com.github.yonaprojects.yona.queue

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.context.event.EventListener
import org.springframework.boot.thread.Threading
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import org.springframework.stereotype.Component
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.min

@Component
class QueueWorkerRuntime(
    private val store: QueueWorkerStore,
    private val registry: TaskRegistry,
    private val clock: QueueClock,
    meterRegistry: MeterRegistry,
    @Value("\${yona.queue.workers:4}") private val workers: Int = 4,
    @Value("\${yona.queue.poll-millis:250}") private val pollMillis: Long = 250,
    @Value("\${yona.queue.lease-millis:60000}") private val leaseMillis: Long = 60_000,
    @Value("\${yona.queue.heartbeat-millis:15000}") private val heartbeatMillis: Long = 15_000,
    @Value("\${yona.queue.shutdown-grace-millis:30000}") private val shutdownGraceMillis: Long = 30_000,
    @Value("\${yona.queue.data-dir:\${yona.data:data}/queue}") dataDirectory: String,
    @Value("\${yona.queue.instance-id:}") configuredInstanceId: String = "",
    @Value("\${spring.datasource.hikari.maximum-pool-size:10}") private val dbConnectionBudget: Int = 10,
    @Value("\${yona.queue.claim-batch:64}") private val claimBatch: Int = 64,
    @Value("\${yona.queue.recovery-poll-millis:0}") configuredRecoveryPollMillis: Long = 0,
    @Value("\${yona.queue.idle-poll-max-millis:1000}") private val idlePollMaxMillis: Long = 1000,
    environment: Environment = StandardEnvironment(),
) : SmartLifecycle, AutoCloseable {
    val instanceId: String = configuredInstanceId.ifBlank { java.util.UUID.randomUUID().toString() }
    private val claiming = AtomicBoolean(true)
    private val closing = AtomicBoolean()
    private val started = AtomicBoolean()
    private val recoveryCursor = AtomicLong()
    private val active = ConcurrentHashMap<QueueAttemptId, WorkerExecution>()
    private val claimGate = ReentrantReadWriteLock()
    private val dataRoot = Path.of(dataDirectory).toAbsolutePath().normalize().also { Files.createDirectories(it) }.toRealPath()
    private val guardRoot = dataRoot.resolve("resource-guards")
    private val threadNumber = AtomicInteger()
    private val slots = Semaphore(workers)
    private val wakeSignal = Semaphore(0)
    private val wakePending = AtomicBoolean()
    private val virtualThreads = Threading.VIRTUAL.isActive(environment)
    private val recoveryPollMillis = if (configuredRecoveryPollMillis == 0L) min(5000L, leaseMillis / 4).coerceAtLeast(1) else configuredRecoveryPollMillis
    private val workerPool by lazy {
        if (virtualThreads) Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("yona-queue-worker-", 0).factory())
        else Executors.newFixedThreadPool(workers, threadFactory("yona-queue-worker"))
    }
    private val heartbeatScheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1, threadFactory("yona-queue-heartbeat-scheduler"))
    private val cleanupScheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1, threadFactory("yona-queue-cleanup"))
    private val heartbeatPool by lazy {
        if (virtualThreads) Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("yona-queue-heartbeat-", 0).factory())
        else Executors.newFixedThreadPool(workers, threadFactory("yona-queue-heartbeat"))
    }
    @Volatile private var poller: Thread? = null
    private val executionTimer = meterRegistry.timer("yona.queue.execution")

    init {
        require(workers > 0 && workers <= dbConnectionBudget) { "Queue worker count exceeds the configured DB connection budget" }
        require(dbConnectionBudget > 0)
        require(claimBatch in 1..64)
        require(pollMillis in 1L..1_000L) { "Queue poll interval must be between 1 and 1000 milliseconds" }
        require(idlePollMaxMillis >= pollMillis && recoveryPollMillis > 0)
        require(leaseMillis > 0 && heartbeatMillis > 0 && heartbeatMillis < leaseMillis) {
            "Queue heartbeat must be positive and shorter than its lease"
        }
        require(shutdownGraceMillis > 0)
        require(instanceId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        Files.createDirectories(guardRoot)
        Gauge.builder("yona.queue.worker.slots.used", this) { (it.workers - it.slots.availablePermits()).toDouble() }.register(meterRegistry)
        Gauge.builder("yona.queue.worker.slots.capacity", this) { it.workers.toDouble() }.register(meterRegistry)
        Gauge.builder("yona.queue.worker.slots.utilization", this) { (it.workers - it.slots.availablePermits()).toDouble() / it.workers }.register(meterRegistry)
    }

    override fun start() {
        if (!started.compareAndSet(false, true) || closing.get()) return
        logger.info("Queue workers use {} threads with {} execution slots", if (virtualThreads) "virtual" else "platform", workers)
        heartbeatScheduler.scheduleWithFixedDelay(
            { heartbeatActive() }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS,
        )
        poller = threadFactory("yona-queue-poller").newThread { pollLoop() }.also { it.start() }
        var cleanupFailed = false
        cleanupScheduler.scheduleWithFixedDelay({
            if (!closing.get()) {
                try {
                    store.cleanupOrphans()
                    if (cleanupFailed) logger.info("Queue orphan cleanup recovered")
                    cleanupFailed = false
                } catch (failure: Exception) {
                    if (!closing.get()) {
                        if (cleanupFailed) logger.debug("Queue orphan cleanup still failing", failure)
                        else logger.warn("Queue orphan cleanup failed; a later pass will retry", failure)
                        cleanupFailed = true
                    }
                }
            }
        }, recoveryPollMillis, recoveryPollMillis, TimeUnit.MILLISECONDS)
    }

    override fun isAutoStartup(): Boolean = true
    override fun getPhase(): Int = Int.MAX_VALUE - 100
    override fun isRunning(): Boolean = started.get() && !closing.get()

    /** Stops future durable claims; active handlers, heartbeat and resource guards remain alive. */
    fun stopClaimingNewWork() {
        claimGate.write { claiming.set(false) }
    }

    @EventListener
    fun onWorkAvailable(event: QueueWorkAvailable) = wake()

    fun wake() {
        if (wakePending.compareAndSet(false, true)) wakeSignal.release()
    }

    fun signalCancellation(jobId: Long) {
        for (execution in active.values) {
            if (execution.token.jobId == jobId) execution.token.cancellationRequested.set(true)
        }
    }

    override fun stop() = close()

    override fun stop(callback: Runnable) {
        try { close() } finally { callback.run() }
    }

    @PreDestroy
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        stopClaimingNewWork()
        active.values.forEach { it.token.shutdownRequested.set(true) }
        poller?.interrupt()
        cleanupScheduler.shutdownNow()
        poller?.takeIf { it !== Thread.currentThread() }?.let { thread ->
            try {
                thread.join(5_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        workerPool.shutdown()
        val terminated = try {
            workerPool.awaitTermination(shutdownGraceMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        heartbeatScheduler.shutdownNow()
        heartbeatPool.shutdownNow()
        val remaining = workers - slots.availablePermits()
        if (!terminated && remaining > 0) {
            logger.warn("Queue shutdown grace expired with {} occupied slot(s); active leases remain for recovery", remaining)
        }
        started.set(false)
    }

    private fun pollLoop() {
        var nextRecovery = 0L
        var nextDue = 0L
        var dueDelay = pollMillis
        var pollFailed = false
        var failureDelay = pollMillis
        while (!closing.get()) {
            try {
                clock.checkSynchronized()
                for (execution in active.values) {
                    try {
                        execution.context.flushProgressIfDue()
                    } catch (_: StaleAttempt) {
                        execution.token.stale.set(true)
                    }
                }
                if (System.nanoTime() >= nextRecovery) {
                    nextRecovery = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(recoveryPollMillis)
                    recoverExpired()
                }
                if (System.nanoTime() >= nextDue) {
                    dueDelay = if (claimDue()) pollMillis else min(idlePollMaxMillis, dueDelay * 2)
                    nextDue = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(dueDelay)
                }
                if (pollFailed) logger.info("Queue runtime polling recovered")
                pollFailed = false
                failureDelay = pollMillis
            } catch (interrupted: InterruptedException) {
                if (closing.get()) break
            } catch (failure: Exception) {
                nextDue = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(failureDelay)
                nextRecovery = nextDue
                failureDelay = min(idlePollMaxMillis, failureDelay * 2)
                if (failure is QueueAdmissionException && failure.code == "CLOCK_UNTRUSTED") {
                    if (pollFailed) logger.debug("Queue runtime polling remains paused: CLOCK_UNTRUSTED")
                    else logger.warn("Queue runtime polling paused: CLOCK_UNTRUSTED")
                } else if (pollFailed) {
                    logger.debug("Queue runtime polling still failing; durable jobs remain available", failure)
                } else {
                    logger.warn("Queue runtime poll failed; durable jobs remain available for a later poll", failure)
                }
                pollFailed = true
            }
            try {
                val waitNanos = (min(nextRecovery, nextDue) - System.nanoTime()).coerceAtLeast(1)
                if (pollFailed) {
                    TimeUnit.NANOSECONDS.sleep(waitNanos)
                } else if (wakeSignal.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
                    wakePending.set(false)
                    dueDelay = pollMillis
                    nextDue = 0L
                }
            } catch (_: InterruptedException) {
                if (closing.get()) break
            }
        }
    }

    private fun recoverExpired() {
        val expired = store.expiredAttemptIds(recoveryCursor.get(), claimBatch)
        if (expired.isNotEmpty()) {
            recoveryCursor.set(expired.last())
            for (id in expired) {
                clock.checkSynchronized()
                store.recoverExpired(id)
            }
        }
    }

    private fun saturatedTypes(): Set<String> = active.values.groupingBy { it.token.definition.type }.eachCount()
        .filter { (type, count) -> active.values.any { it.token.definition.type == type && count >= it.token.definition.laneLimit } }
        .keys

    private fun claimDue(): Boolean {
        var remainingClaims = slots.availablePermits()
        if (!claiming.get() || closing.get() || remainingClaims == 0) return false
        var cursor: QueueDueCursor? = null
        var found = false
        repeat(4) {
            if (remainingClaims == 0 || closing.get() || !claiming.get()) return found
            val candidates = store.dueCandidateIds(cursor, claimBatch, saturatedTypes())
            if (candidates.isEmpty()) return found
            found = true
            cursor = candidates.last()
            for (candidate in candidates) {
                if (remainingClaims == 0 || closing.get() || !claiming.get() || !slots.tryAcquire()) return found
                var dispatched = false
                try {
                    claimGate.read {
                        if (claiming.get() && !closing.get()) dispatched = claimCandidate(candidate.id)
                    }
                } finally {
                    if (dispatched) remainingClaims-- else slots.release()
                }
            }
            if (candidates.size < claimBatch) return found
        }
        return found
    }

    private fun claimCandidate(jobId: Long): Boolean {
        clock.checkSynchronized()
        val candidate = store.candidate(jobId) ?: return false
        val definition = registry.find(candidate.taskType, candidate.payloadVersion)
        if (definition?.handler == null) {
            store.markUnsupported(candidate)
            return false
        }
        val decoded = try {
            registry.decodeAndValidate(definition, candidate.payload)
        } catch (_: Exception) {
            store.markUnsupported(candidate, "INVALID_PAYLOAD", "Stored payload does not satisfy the registered task definition.")
            return false
        }
        if (decoded.resourceKeys != candidate.resourceKeys) {
            store.markUnsupported(candidate, "RESOURCE_MISMATCH", "Stored resource keys differ from the registered task definition.")
            return false
        }
        if (active.values.count { it.token.definition.type == definition.type } >= definition.laneLimit) return false
        val guardSet = ResourceGuardSet.tryAcquire(guardRoot, decoded.resourceKeys) ?: return false
        var execution: WorkerExecution? = null
        var dispatched = false
        try {
            clock.checkSynchronized()
            val token = store.claim(candidate, definition, decoded, instanceId) ?: return false
            val claimed = WorkerExecution(token, TaskContext(
                token.jobId, token.attemptNo, token.fence, token.ownerInstance, token, store,
            ), guardSet)
            execution = claimed
            check(active.putIfAbsent(claimed.key, claimed) == null) { "Duplicate local queue attempt" }
            workerPool.execute { runHandler(claimed) }
            dispatched = true
            return true
        } catch (failure: Throwable) {
            execution?.let {
                active.remove(it.key, it)
                runCatching { store.releaseAfterHandlerReturn(it.token) }
            }
            throw failure
        } finally {
            if (!dispatched) guardSet.close()
        }
    }

    private fun runHandler(execution: WorkerExecution) {
        val startedAt = System.nanoTime()
        val token = execution.token
        var failure: Throwable? = null
        try {
            checkNotNull(token.definition.handler).invoke(execution.context, token.payload)
        } catch (thrown: Throwable) {
            failure = thrown
        }
        try {
            val staged = execution.context.closeForHandlerReturn()
            try {
                store.complete(token, failure, staged, execution.context.finalProgress())
            } catch (stale: StaleAttempt) {
                token.stale.set(true)
            } catch (persistFailure: Exception) {
                logger.warn("Queue handler outcome could not be committed for job {} attempt {}", token.jobId, token.attemptNo, persistFailure)
            }
        } catch (contextFailure: Exception) {
            logger.warn("Queue task context could not close for job {} attempt {}", token.jobId, token.attemptNo, contextFailure)
        } finally {
            // This is the only path that clears DB ownership: the handler and all context operations returned.
            runCatching { store.releaseAfterHandlerReturn(token) }
                .onFailure { logger.warn("Queue resource-owner cleanup failed for job {}", token.jobId, it) }
            try {
                store.deleteStaging(token)
                execution.guardSet.close()
                active.remove(execution.key, execution)
                executionTimer.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
            } finally {
                slots.release()
                wake()
            }
        }
    }

    private fun heartbeatActive() {
        for (execution in active.values) {
            if (execution.token.stale.get() || !execution.heartbeatInFlight.compareAndSet(false, true)) continue
            try {
                heartbeatPool.execute {
                    try {
                        if (!store.heartbeat(execution.token)) execution.token.stale.set(true)
                    } catch (failure: Exception) {
                        // A DB outage is not proof that the lease is revoked; recovery decides after expiry.
                        logger.warn("Queue heartbeat failed for job {} attempt {}", execution.token.jobId, execution.token.attemptNo, failure)
                    } finally {
                        execution.heartbeatInFlight.set(false)
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                execution.heartbeatInFlight.set(false)
                if (!closing.get()) throw rejected
            }
        }
    }

    private fun threadFactory(prefix: String): ThreadFactory = ThreadFactory { task ->
        Thread(task, "$prefix-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }

    private data class WorkerExecution(
        val token: QueueAttemptToken,
        val context: TaskContext,
        val guardSet: ResourceGuardSet,
    ) {
        val key = QueueAttemptId(token.jobId, token.attemptNo)
        val heartbeatInFlight = AtomicBoolean()
    }

    private class ResourceGuardSet private constructor(private val held: List<HeldGuard>) : AutoCloseable {
        override fun close() {
            held.asReversed().forEach { guard ->
                runCatching { guard.lock.release() }
                runCatching { guard.channel.close() }
            }
        }

        private data class HeldGuard(val channel: FileChannel, val lock: FileLock)

        companion object {
            fun tryAcquire(root: Path, resourceKeys: List<String>): ResourceGuardSet? {
                val held = mutableListOf<HeldGuard>()
                try {
                    for (key in resourceKeys) {
                        val name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))
                        val path = root.resolve("$name.lock").normalize()
                        require(path.startsWith(root))
                        val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                        val lock = try {
                            channel.tryLock()
                        } catch (_: OverlappingFileLockException) {
                            null
                        } catch (failure: Throwable) {
                            runCatching { channel.close() }
                            throw failure
                        }
                        if (lock == null) {
                            channel.close()
                            held.asReversed().forEach { runCatching { it.lock.release() }; runCatching { it.channel.close() } }
                            return null
                        }
                        held += HeldGuard(channel, lock)
                    }
                    return ResourceGuardSet(held)
                } catch (failure: Throwable) {
                    held.asReversed().forEach { runCatching { it.lock.release() }; runCatching { it.channel.close() } }
                    throw failure
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(QueueWorkerRuntime::class.java)
    }
}
