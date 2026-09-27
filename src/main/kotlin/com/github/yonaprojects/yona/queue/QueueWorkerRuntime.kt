package com.github.yonaprojects.yona.queue

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
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
    @Value("\${yona.queue.workers:4}") private val workers: Int = 4,
    @Value("\${yona.queue.poll-millis:250}") private val pollMillis: Long = 250,
    @Value("\${yona.queue.lease-millis:60000}") private val leaseMillis: Long = 60_000,
    @Value("\${yona.queue.heartbeat-millis:15000}") private val heartbeatMillis: Long = 15_000,
    @Value("\${yona.queue.shutdown-grace-millis:30000}") private val shutdownGraceMillis: Long = 30_000,
    @Value("\${yona.queue.data-dir:\${yona.data:data}/queue}") dataDirectory: String,
    @Value("\${yona.queue.instance-id:}") configuredInstanceId: String = "",
    @Value("\${spring.datasource.hikari.maximum-pool-size:10}") private val dbConnectionBudget: Int = 10,
) : SmartLifecycle, AutoCloseable {
    val instanceId: String = configuredInstanceId.ifBlank { java.util.UUID.randomUUID().toString() }
    private val claiming = AtomicBoolean(true)
    private val closing = AtomicBoolean()
    private val started = AtomicBoolean()
    private val scanCursor = AtomicLong()
    private val recoveryCursor = AtomicLong()
    private val active = ConcurrentHashMap<QueueAttemptId, WorkerExecution>()
    private val claimGate = ReentrantReadWriteLock()
    private val dataRoot = Path.of(dataDirectory).toAbsolutePath().normalize().also { Files.createDirectories(it) }.toRealPath()
    private val guardRoot = dataRoot.resolve("resource-guards")
    private val threadNumber = AtomicInteger()
    private val workerPool by lazy {
        ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(workers), threadFactory("yona-queue-worker"))
    }
    private val heartbeatPool = java.util.concurrent.ScheduledThreadPoolExecutor(1, threadFactory("yona-queue-heartbeat"))
    @Volatile private var poller: Thread? = null

    init {
        require(workers > 0 && workers <= dbConnectionBudget) { "Queue worker count exceeds the configured DB connection budget" }
        require(dbConnectionBudget > 0)
        require(pollMillis in 1L..1_000L) { "Queue poll interval must be between 1 and 1000 milliseconds" }
        require(leaseMillis > 0 && heartbeatMillis > 0 && heartbeatMillis < leaseMillis) {
            "Queue heartbeat must be positive and shorter than its lease"
        }
        require(shutdownGraceMillis > 0)
        require(instanceId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        Files.createDirectories(guardRoot)
    }

    override fun start() {
        if (!started.compareAndSet(false, true) || closing.get()) return
        heartbeatPool.scheduleWithFixedDelay(
            { heartbeatActive() }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS,
        )
        poller = threadFactory("yona-queue-poller").newThread { pollLoop() }.also { it.start() }
    }

    override fun isAutoStartup(): Boolean = true
    override fun getPhase(): Int = Int.MAX_VALUE - 100
    override fun isRunning(): Boolean = started.get() && !closing.get()

    /** Stops future durable claims; active handlers, heartbeat and resource guards remain alive. */
    fun stopClaimingNewWork() {
        claimGate.write { claiming.set(false) }
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
        heartbeatPool.shutdownNow()
        if (!terminated) {
            logger.warn("Queue shutdown grace expired with {} active handler(s); active leases remain for recovery", active.size)
        }
        started.set(false)
    }

    private fun pollLoop() {
        while (!closing.get()) {
            try {
                clock.checkSynchronized()
                recoverAndClaim()
            } catch (interrupted: InterruptedException) {
                if (closing.get()) break
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                logger.warn("Queue runtime poll failed; durable jobs remain available for a later poll", failure)
            }
            try {
                Thread.sleep(pollMillis)
            } catch (_: InterruptedException) {
                if (closing.get()) break
            }
        }
    }

    private fun recoverAndClaim() {
        val expired = store.expiredAttemptIds(recoveryCursor.get(), 64)
        if (expired.isNotEmpty()) {
            recoveryCursor.set(expired.last())
            for (id in expired) {
                clock.checkSynchronized()
                runCatching { store.recoverExpired(id) }
                    .onFailure { logger.warn("Queue lease recovery failed for job {}", id, it) }
            }
        }
        if (!claiming.get() || closing.get()) return
        val freeSlots = workers - active.size
        if (freeSlots <= 0) return
        val limit = min(64, maxOf(16, freeSlots.coerceAtMost(8) * 8))
        val ids = store.dueCandidateIds(scanCursor.get(), limit)
        if (ids.isEmpty()) return
        scanCursor.set(ids.last())
        for (id in ids) {
            if (closing.get() || active.size >= workers) break
            claimGate.read {
                if (claiming.get() && !closing.get()) claimCandidate(id)
            }
        }
    }

    private fun claimCandidate(jobId: Long) {
        clock.checkSynchronized()
        val candidate = store.candidate(jobId) ?: return
        val definition = registry.find(candidate.taskType, candidate.payloadVersion)
        if (definition?.handler == null) {
            store.markUnsupported(candidate)
            return
        }
        val decoded = try {
            registry.decodeAndValidate(definition, candidate.payload)
        } catch (_: Exception) {
            store.markUnsupported(candidate)
            return
        }
        if (decoded.resourceKeys != candidate.resourceKeys) {
            store.markUnsupported(candidate)
            return
        }
        if (active.values.count { it.token.definition.type == definition.type } >= definition.laneLimit) return
        val guardSet = ResourceGuardSet.tryAcquire(guardRoot, decoded.resourceKeys) ?: return
        val token = try {
            clock.checkSynchronized()
            store.claim(candidate, definition, decoded, instanceId)
        } catch (failure: Exception) {
            guardSet.close()
            throw failure
        } ?: run {
            guardSet.close()
            return
        }
        val execution = WorkerExecution(token, TaskContext(
            token.jobId, token.attemptNo, token.fence, token.ownerInstance, token, store,
        ), guardSet)
        check(active.putIfAbsent(execution.key, execution) == null) { "Duplicate local queue attempt" }
        try {
            workerPool.execute { runHandler(execution) }
        } catch (failure: RuntimeException) {
            active.remove(execution.key, execution)
            runCatching { store.releaseAfterHandlerReturn(token) }
            guardSet.close()
            throw failure
        }
    }

    private fun runHandler(execution: WorkerExecution) {
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
                store.complete(token, failure, staged)
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
            execution.guardSet.close()
            active.remove(execution.key, execution)
        }
    }

    private fun heartbeatActive() {
        for (execution in active.values) {
            try {
                if (!store.heartbeat(execution.token)) execution.token.stale.set(true)
            } catch (failure: Exception) {
                // A DB outage is not proof that the lease is revoked; recovery decides after expiry.
                logger.warn("Queue heartbeat failed for job {} attempt {}", execution.token.jobId, execution.token.attemptNo, failure)
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
