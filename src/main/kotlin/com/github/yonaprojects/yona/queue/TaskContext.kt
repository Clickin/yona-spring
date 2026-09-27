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
    val safeSummary: String = message.filter { it == '\t' || it == ' ' || it >= ' ' }
        .replace(Regex("\\s+"), " ").take(4096)

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
    private val artifactPaths = mutableSetOf<String>()
    private var closed = false

    fun checkpoint() = lifetime.read {
        ensureOpen()
        val status = store.assertCurrent(token)
        if (status == QueueStatus.CANCEL_REQUESTED || token.cancellationRequested.get() ||
            token.shutdownRequested.get()) throw CooperativeTaskCancellation()
    }

    fun progress(stage: String, counters: Map<String, Long> = emptyMap()) = lifetime.read {
        ensureOpen()
        store.progress(token, stage, counters)
    }

    fun <T> fencedDb(block: (EntityManager) -> T): T = lifetime.read {
        ensureOpen()
        store.fencedDb(token, block)
    }

    /** Streams into private staging; only successful fenced completion can publish it. */
    fun writeArtifact(relativePath: String, writer: (OutputStream) -> Unit) = lifetime.read {
        ensureOpen()
        store.assertCurrent(token)
        val path = normalizeRelativePath(relativePath)
        synchronized(artifacts) {
            check(artifactPaths.add(path)) { "Artifact path was already written by this attempt" }
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
            synchronized(artifacts) { artifactPaths.remove(path) }
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
        val path = Path.of(value)
        require(!path.isAbsolute) { "Artifact path must be relative" }
        val normalized = path.normalize()
        require(normalized.nameCount > 0 && normalized.none { it.toString() == ".." }) { "Invalid artifact path" }
        val result = normalized.joinToString("/") { it.toString() }
        require(result.isNotBlank() && result.length <= 1024) { "Invalid artifact path" }
        return result
    }
}
