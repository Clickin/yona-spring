package com.github.yonaprojects.yona.queue

import jakarta.persistence.EntityManager
import tools.jackson.databind.JsonNode
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class StaleAttempt : IllegalStateException("Queue attempt lease or fence is no longer current")
class CooperativeTaskCancellation : RuntimeException("Queue task reached a cooperative cancellation checkpoint")

/** Callers supply a display-safe message; unexpected exceptions never persist raw messages. */
sealed class ClassifiedTaskFailure(
    message: String,
    val code: String,
) : RuntimeException(message) {
    val safeSummary: String = safeQueueError(message)

    init {
        require(code.matches(Regex("[A-Z0-9_.-]{1,80}")))
        require(safeSummary.isNotBlank())
    }
}

class RetryableTaskFailure(message: String, code: String = "RETRYABLE_TASK_FAILURE") :
    ClassifiedTaskFailure(message, code)
class PermanentTaskFailure(message: String, code: String = "PERMANENT_TASK_FAILURE") :
    ClassifiedTaskFailure(message, code)
class RecoveryRequiredTaskFailure(message: String, code: String = "RECOVERY_REQUIRED") :
    ClassifiedTaskFailure(message, code)

internal data class StagedQueueArtifact(
    val relativePath: String,
    val stagingPath: Path,
    val sizeBytes: Long,
    val sha256: String,
)

internal class QueueProgress(val stage: String, counters: Map<String, Long>) {
    val counters = counters.also { require(it.size <= 16) { "Invalid progress counters" } }.toSortedMap()

    init {
        require(stage.isNotBlank() && validQueueText(stage, 160)) { "Invalid progress stage" }
        require(this.counters.size <= 16 && this.counters.all { (key, value) ->
            key.matches(COUNTER_KEY) && value >= 0
        }) { "Invalid progress counters" }
    }

    private companion object {
        val COUNTER_KEY = Regex("[a-zA-Z0-9_.-]{1,64}")
    }
}

internal class QueueAttemptToken(
    val jobId: Long,
    val attemptNo: Long,
    val executionGeneration: Long,
    val fence: Long,
    val ownerInstance: String,
    val resourceFences: Map<String, Long>,
    val definition: TaskDefinition,
    val payload: JsonNode,
    val stale: AtomicBoolean = AtomicBoolean(),
    val cancellationRequested: AtomicBoolean = AtomicBoolean(),
    val shutdownRequested: AtomicBoolean = AtomicBoolean(),
)

/** The only handler surface; queue writes are always short and checked against the live DB fence. */
class TaskContext internal constructor(
    val jobId: Long,
    val attemptNo: Long,
    val fence: Long,
    val ownerInstance: String,
    private val token: QueueAttemptToken,
    private val store: QueueWorkerStore,
) {
    private val lifetime = ReentrantReadWriteLock()
    private val artifacts = mutableListOf<StagedQueueArtifact>()
    private var artifactReserved = false
    private var closed = false
    private val progressLock = Any()
    private var pendingProgress: QueueProgress? = null
    private var lastProgressStage: String? = null
    private var lastProgressAt = 0L

    fun checkpoint() = lifetime.read {
        ensureOpen()
        val status = store.assertCurrent(token)
        if (status == QueueStatus.CANCEL_REQUESTED || token.cancellationRequested.get() ||
            token.shutdownRequested.get()) throw CooperativeTaskCancellation()
    }

    fun progress(stage: String, counters: Map<String, Long> = emptyMap()) = lifetime.read {
        ensureOpen()
        val snapshot = QueueProgress(stage, counters)
        synchronized(progressLock) {
            if (stage != lastProgressStage || progressDue()) {
                persistProgress(snapshot)
            } else {
                pendingProgress = snapshot
            }
        }
    }

    internal fun flushProgressIfDue() = lifetime.read {
        if (closed || token.stale.get()) return@read
        synchronized(progressLock) {
            pendingProgress?.takeIf { progressDue() }?.let { persistProgress(it) }
        }
    }

    internal fun finalProgress(): QueueProgress? = lifetime.read {
        check(closed)
        pendingProgress
    }

    private fun progressDue(): Boolean = System.nanoTime() - lastProgressAt >= 5_000_000_000L

    private fun persistProgress(snapshot: QueueProgress) {
        store.progress(token, snapshot)
        pendingProgress = null
        lastProgressStage = snapshot.stage
        lastProgressAt = System.nanoTime()
    }

    fun <T> fencedDb(block: (EntityManager) -> T): T = lifetime.read {
        ensureOpen()
        // Keep the progress gate before the DB fence, including reentrant progress calls.
        synchronized(progressLock) {
            val pendingBefore = pendingProgress
            val stageBefore = lastProgressStage
            val timeBefore = lastProgressAt
            try {
                store.fencedDb(token, block).also {
                    if (lastProgressAt != timeBefore) lastProgressAt = System.nanoTime()
                }
            } catch (failure: Throwable) {
                pendingProgress = pendingBefore
                lastProgressStage = stageBefore
                lastProgressAt = timeBefore
                throw failure
            }
        }
    }

    /** Streams into private staging; only successful fenced completion can publish it. */
    fun writeArtifact(relativePath: String, writer: (OutputStream) -> Unit) = lifetime.read {
        ensureOpen()
        store.assertCurrent(token)
        val path = normalizeRelativePath(relativePath)
        synchronized(artifacts) {
            check(!artifactReserved) { "A queue job may publish only one result artifact" }
            artifactReserved = true
        }
        val directory = store.stagingDirectory(token)
        var staging: Path? = null
        try {
            Files.createDirectories(directory)
            val stagingPath = Files.createTempFile(directory, "artifact-", ".part")
            staging = stagingPath
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newOutputStream(stagingPath, StandardOpenOption.WRITE).use { raw ->
                val digesting = DigestOutputStream(raw, digest)
                val counted = object : FilterOutputStream(digesting) {
                    override fun write(value: Int) {
                        val next = Math.addExact(size, 1L)
                        out.write(value)
                        size = next
                    }

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        val next = Math.addExact(size, length.toLong())
                        out.write(bytes, offset, length)
                        size = next
                    }
                }
                writer(counted)
            }
            FileChannel.open(stagingPath, StandardOpenOption.WRITE).use { it.force(true) }
            store.assertCurrent(token)
            synchronized(artifacts) {
                artifacts += StagedQueueArtifact(path, stagingPath, size, HexFormat.of().formatHex(digest.digest()))
            }
        } catch (failure: Throwable) {
            staging?.let { runCatching { Files.deleteIfExists(it) } }
            synchronized(artifacts) { artifactReserved = false }
            throw failure
        }
    }

    internal fun closeForHandlerReturn(): List<StagedQueueArtifact> = lifetime.write {
        closed = true
        synchronized(artifacts) { artifacts.toList() }
    }

    private fun ensureOpen() {
        if (closed || token.stale.get()) throw StaleAttempt()
    }

    private fun normalizeRelativePath(value: String): String {
        require(value.isNotBlank() && value.length <= 1024 && '\\' !in value) { "Invalid artifact path" }
        require(validQueueText(value, 1024)) { "Invalid artifact path text" }
        require(value.toByteArray(Charsets.UTF_8).size <= 1024) { "Artifact path exceeds the byte limit" }
        val path = Path.of(value)
        require(!path.isAbsolute) { "Artifact path must be relative" }
        val normalized = path.normalize()
        require(normalized.nameCount > 0 && normalized.none { it.toString() == ".." }) { "Invalid artifact path" }
        require(normalized.fileName.toString().toByteArray(Charsets.UTF_8).size <= 255) { "Artifact filename exceeds the portable byte limit" }
        val result = normalized.joinToString("/") { it.toString() }
        require(result.isNotBlank() && result.length <= 1024) { "Invalid artifact path" }
        return result
    }
}

internal fun validQueueText(value: String, maximumCodePoints: Int): Boolean {
    var index = 0
    var count = 0
    while (index < value.length) {
        val point = value.codePointAt(index)
        if (point < 0x20 || point == 0x7f || point in 0xd800..0xdfff || ++count > maximumCodePoints) return false
        index += Character.charCount(point)
    }
    return true
}

private fun safeQueueError(value: String): String = buildString(minOf(value.length, 4096)) {
    var index = 0
    var count = 0
    while (index < value.length && count < 2048) {
        val point = value.codePointAt(index)
        index += Character.charCount(point)
        require(point !in 0xd800..0xdfff) { "Invalid error text" }
        if ((point < 0x20 && point != 0x09) || point == 0x7f) continue
        if (point == 0x09 || point == 0x20) {
            if (isNotEmpty() && last() == ' ') continue
            append(' ')
        } else {
            appendCodePoint(point)
        }
        count++
    }
}
