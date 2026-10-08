package com.github.yonaprojects.yona.domain.vcs

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.tmatesoft.svn.core.SVNErrorCode
import org.tmatesoft.svn.core.SVNException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

@Component
class RepositoryMirrorDispatcher(
    private val store: RepositoryMirrorStore,
    private val synchronizer: SvnMirrorSynchronizer,
    private val properties: RepositoryMirrorProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closing = AtomicBoolean(false)
    private val owner = UUID.randomUUID().toString()
    private val slots = Semaphore(properties.workers.coerceIn(1, 8))
    private val active = ConcurrentHashMap<Long, AtomicBoolean>()
    internal val executor = ThreadPoolExecutor(properties.workers.coerceIn(1, 8), properties.workers.coerceIn(1, 8), 0L, TimeUnit.SECONDS, SynchronousQueue(), java.util.concurrent.ThreadFactory { task -> Thread(task, "svn-mirror-worker") })
    private val heartbeats = ScheduledThreadPoolExecutor(1, java.util.concurrent.ThreadFactory { task -> Thread(task, "svn-mirror-heartbeat") }).apply { removeOnCancelPolicy = true }

    @Scheduled(fixedDelayString = "\${yona.repository-mirror.dispatch-millis:1000}")
    fun dispatch() {
        if (closing.get() || !properties.enabled || properties.nodeId != properties.writerNodeId) return
        properties.requireActivation()
        for (id in store.due(properties.workers.coerceIn(1, 8))) {
            if (closing.get() || !slots.tryAcquire()) return
            var fence: Long? = null
            try {
                fence = store.claim(id, owner, properties.leaseSeconds)
                if (fence == null) {
                    slots.release()
                    continue
                }
                val claimedFence = fence
                executor.execute { execute(id, claimedFence) }
            } catch (_: RejectedExecutionException) {
                try { fence?.let { store.release(id, owner, it) } }
                catch (_: Exception) { logger.warn("Mirror {} rejected lease will recover on expiry", id) }
                finally { slots.release() }
            } catch (e: Exception) {
                slots.release()
                // Claim outcome may be unknown. Its bounded lease makes it discoverable again.
                logger.warn("Mirror admission failed ({})", e.javaClass.simpleName)
            }
        }
    }

    private fun execute(id: Long, fence: Long) {
        val cancelled = AtomicBoolean(closing.get())
        active[id] = cancelled
        var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            heartbeat = heartbeats.scheduleAtFixedRate({
                try {
                    if (!store.heartbeat(id, owner, fence, properties.leaseSeconds)) cancelled.set(true)
                } catch (_: Exception) {
                    cancelled.set(true)
                }
            }, 0, maxOf(1, properties.leaseSeconds / 3), TimeUnit.SECONDS)
            synchronizer.syncOneMirror(id, owner, fence) { cancelled.get() || closing.get() }
        } catch (_: MirrorLeaseLost) {
            // Pause, shutdown and stale execution never publish progress or steal another lease.
        } catch (failure: Exception) {
            if (!cancelled.get() && !closing.get()) {
                try { store.fail(id, owner, fence, classify(failure)) }
                catch (_: Exception) { logger.warn("Mirror {} failure state could not be published", id) }
            }
        } finally {
            heartbeat?.cancel(false)
            active.remove(id)
            try { store.release(id, owner, fence) }
            catch (_: Exception) { logger.warn("Mirror {} lease will recover on expiry", id) }
            slots.release()
        }
    }

    @PreDestroy
    fun close() {
        closing.set(true)
        active.values.forEach { it.set(true) }
        executor.shutdown()
        var interrupted = false
        // A lease or shutdown timeout is not evidence that an SVN writer stopped.
        while (!executor.isTerminated) {
            try { executor.awaitTermination(1, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        heartbeats.shutdown()
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        internal fun classify(error: Exception): MirrorFailure {
            if (error is MirrorFailure) return error
            val causes = generateSequence(error as Throwable?) { it.cause }.take(12).toList()
            if (causes.any { it is SSLException }) return MirrorFailure("SOURCE_TLS_REJECTED")
            val svn = causes.filterIsInstance<SVNException>().firstOrNull()?.errorMessage?.errorCode
            if (svn?.isAuthentication == true || svn?.category == SVNErrorCode.AUTHZ_CATEGORY) return MirrorFailure("SOURCE_AUTH_REJECTED")
            if (svn?.category == SVNErrorCode.FS_CATEGORY || svn == SVNErrorCode.CHECKSUM_MISMATCH || svn == SVNErrorCode.RA_LOCAL_REPOS_OPEN_FAILED) return MirrorFailure("REPOSITORY_DAMAGED", attention = true)
            if (causes.any { it is SocketTimeoutException || it is ConnectException } || svn == SVNErrorCode.RA_DAV_CONN_TIMEOUT || svn == SVNErrorCode.RA_DAV_REQUEST_FAILED) return MirrorFailure("SOURCE_UNAVAILABLE", transient = true)
            return MirrorFailure("SYNC_FAILED")
        }
    }
}
