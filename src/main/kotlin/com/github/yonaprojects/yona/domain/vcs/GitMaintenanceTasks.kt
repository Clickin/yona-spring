package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.queue.CooperativeTaskCancellation
import com.github.yonaprojects.yona.queue.PermanentTaskFailure
import com.github.yonaprojects.yona.queue.RetryableTaskFailure
import com.github.yonaprojects.yona.queue.TaskContext
import com.github.yonaprojects.yona.queue.TaskDefinition
import org.eclipse.jgit.errors.CancelledException
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.internal.storage.file.GC
import org.eclipse.jgit.lib.GcConfig
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.storage.pack.PackConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant

/** Definitions are independent of Queue so application task registration stays acyclic. */
@Configuration(proxyBeanMethods = false)
class GitMaintenanceTasks(
    private val projects: ProjectRepository,
    @Value("\${yona.git.base-dir:/tmp/yona/git}") baseDirectory: File,
    @Value("\${yona.git.maintenance.idle-millis:3600000}") private val idleMillis: Long = 3_600_000,
) {
    private val root = baseDirectory.toPath().toAbsolutePath().normalize()
    private val logger = LoggerFactory.getLogger(javaClass)

    init { require(idleMillis > 0) }

    @Bean
    fun gitMaintenanceTask() = TaskDefinition(
        type = TYPE,
        payloadVersion = 1,
        validate = { node ->
            require(node.properties().map { it.key }.toSet() == setOf("projectId", "automatic"))
            require(node["projectId"].isIntegralNumber && node["projectId"].canConvertToLong() && node["projectId"].longValue() > 0)
            require(node["automatic"].isBoolean)
        },
        // The archive guard also excludes a stale site restore/export, beyond its database lease.
        resourceKeys = { node -> listOf(resource(node["projectId"].longValue()), "site:archive") },
        replaySafe = true,
        handler = { context, payload ->
            val project = projects.findById(payload["projectId"].longValue()).orElse(null)
                ?: throw PermanentTaskFailure("The project no longer exists.", "GIT_PROJECT_NOT_FOUND")
            if (!(project.vcs ?: "GIT").equals("GIT", ignoreCase = true)) {
                throw PermanentTaskFailure("Git maintenance requires a Git project.", "GIT_PROJECT_REQUIRED")
            }
            try {
                val directory = directory(project)
                withRepositoryLock(directory) {
                    context.checkpoint()
                    val current = projects.findById(project.id!!).orElse(null)
                        ?: throw PermanentTaskFailure("The project no longer exists.", "GIT_PROJECT_NOT_FOUND")
                    if (current.owner != project.owner || current.name != project.name || !(current.vcs ?: "GIT").equals("GIT", ignoreCase = true)) {
                        throw RetryableTaskFailure("The project moved while maintenance was starting.", "GIT_PROJECT_MOVED")
                    }
                    if (payload["automatic"].booleanValue()) {
                        val cutoff = Instant.now().minusMillis(idleMillis)
                        val lastWrite = current.lastPushedDate ?: current.createdDate
                        if (lastWrite == null || lastWrite.isAfter(cutoff) || !idle(directory, cutoff)) {
                            context.progress("git.gc.skipped-active")
                            return@withRepositoryLock
                        }
                    }
                    context.progress("git.gc.running")
                    collect(directory, QueueGcMonitor(context))
                    context.checkpoint()
                    context.progress("git.gc.complete")
                }
            } catch (_: CancelledException) {
                context.checkpoint()
                throw CooperativeTaskCancellation()
            } catch (_: IllegalArgumentException) {
                throw PermanentTaskFailure("The Git repository is unavailable or has an invalid location.", "GIT_REPOSITORY_UNAVAILABLE")
            } catch (failure: IOException) {
                logger.warn("Git maintenance IO failure for project {}", project.id, failure)
                throw RetryableTaskFailure("The repository could not be maintained; maintenance can be retried safely.", "GIT_GC_IO_FAILURE")
            }
        },
    )

    internal fun directory(project: Project): Path {
        require((project.vcs ?: "GIT").equals("GIT", ignoreCase = true)) { "Git project required" }
        val owner = project.owner ?: throw IllegalArgumentException("Project owner required")
        require(validSegment(owner) && validSegment(project.name)) { "Invalid repository identity" }
        val directory = root.resolve(owner).resolve("${project.name}.git")
        require(Files.isDirectory(directory, NOFOLLOW_LINKS) && Files.isDirectory(directory.resolve("objects"), NOFOLLOW_LINKS) &&
            Files.isRegularFile(directory.resolve("HEAD"), NOFOLLOW_LINKS)) { "Git repository is unavailable" }
        require(directory.toRealPath().startsWith(root.toRealPath())) { "Repository is outside the Git data directory" }
        return directory
    }

    /** Stable inode: never unlink this advisory lock, including after an unsuccessful attempt. */
    internal fun <T> withRepositoryLock(directory: Path, block: () -> T): T =
        FileChannel.open(directory.resolve("yona-gc.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw RetryableTaskFailure("Git maintenance is already running for this repository.", "GIT_GC_BUSY")
            lock.use { block() }
        }

    internal fun collect(directory: Path, monitor: ProgressMonitor) {
        FileRepositoryBuilder().setGitDir(directory.toFile()).setMustExist(true).build().use { repository ->
            val repo = repository as FileRepository
            // JGit's old-pack cleanup consults this string even when an explicit expiration is supplied.
            // This is an in-memory override only; a repository's persisted configuration is not changed.
            repo.config.setString("gc", null, "pruneExpire", "never")
            val gc = GC(repo)
            gc.setExpire(Instant.ofEpochMilli(Long.MIN_VALUE))
            gc.setPackExpire(Instant.now().minusSeconds(3600))
            gc.setPackConfig(PackConfig().apply {
                setThreads(1)
                setPrunePreserved(false)
            })
            gc.setGcConfig(GcConfig(GcConfig.PackRefsMode.FALSE))
            gc.setProgressMonitor(monitor)
            // No aggressive settings, reflog expiry or unreachable-object pruning. Unmatched objects
            // in old packs are loosened before replacement; in-flight receives/web writes retain them.
            gc.gc().join()
            if (monitor is QueueGcMonitor && !monitor.started) {
                throw RetryableTaskFailure("Another Git collector is running for this repository.", "GIT_GC_BUSY")
            }
        }
    }

    /** Ref files cover web commits; object directories also cover an in-progress receive before its post hook. */
    internal fun idle(directory: Path, cutoff: Instant): Boolean {
        for (name in listOf("HEAD", "packed-refs", "objects", "objects/pack", "refs")) {
            val path = directory.resolve(name)
            if (Files.exists(path, NOFOLLOW_LINKS) && Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toInstant().isAfter(cutoff)) return false
        }
        Files.newDirectoryStream(directory.resolve("objects")).use { children ->
            for (path in children) {
                if (Files.isDirectory(path, NOFOLLOW_LINKS) && Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toInstant().isAfter(cutoff)) return false
            }
        }
        val refs = directory.resolve("refs")
        if (Files.isDirectory(refs, NOFOLLOW_LINKS)) {
            Files.walk(refs).use { paths ->
                if (paths.anyMatch { Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toInstant().isAfter(cutoff) }) return false
            }
        }
        return true
    }

    private class QueueGcMonitor(private val context: TaskContext) : ProgressMonitor {
        var started = false
            private set
        private var lastCheck = 0L
        override fun start(totalTasks: Int) { started = true; check() }
        override fun beginTask(title: String, totalWork: Int) { check() }
        override fun update(completed: Int) = Unit
        override fun endTask() { check() }
        override fun showDuration(enabled: Boolean) = Unit
        override fun isCancelled(): Boolean {
            // ponytail: one DB cancellation/fence check per second, not one query per packed object.
            if (System.nanoTime() - lastCheck >= 1_000_000_000L) check()
            return Thread.currentThread().isInterrupted
        }
        private fun check() {
            context.checkpoint()
            if (Thread.currentThread().isInterrupted) throw CooperativeTaskCancellation()
            lastCheck = System.nanoTime()
        }
    }

    companion object {
        const val TYPE = "git.gc"
        fun resource(projectId: Long) = "git:$projectId"
        private fun validSegment(value: String) = value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 }
    }
}
