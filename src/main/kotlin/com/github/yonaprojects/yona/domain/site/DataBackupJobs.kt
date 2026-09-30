package com.github.yonaprojects.yona.domain.site

import com.github.yonaprojects.yona.queue.CooperativeTaskCancellation
import com.github.yonaprojects.yona.queue.PermanentTaskFailure
import com.github.yonaprojects.yona.queue.Queue
import com.github.yonaprojects.yona.queue.RecoveryRequiredTaskFailure
import com.github.yonaprojects.yona.queue.StaleAttempt
import com.github.yonaprojects.yona.queue.TaskDefinition
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipInputStream

/** HTTP producers only submit; the existing durable queue owns execution and result publication. */
@Service
class DataBackupJobs(
    private val queue: Queue,
    private val tasks: DataBackupJobTasks,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val admission = TransactionTemplate(transactionManager)

    @Transactional
    fun export(project: String?, callerScope: String): Long = queue.enqueue(
        DataBackupJobTasks.EXPORT, 1, objectMapper.writeValueAsBytes(mapOf("project" to project)),
        Instant.EPOCH, null, callerScope,
    ).jobId

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun import(input: InputStream, callerScope: String): Long {
        val reference = tasks.stageInput(input)
        return admission.execute {
            // A failed/unknown commit may already have admitted the job. Only a confirmed rollback permits deletion.
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        runCatching { Files.deleteIfExists(tasks.inputPath(reference.getValue("input").toString())) }
                    }
                }
            })
            queue.enqueue(
                DataBackupJobTasks.IMPORT, 1, objectMapper.writeValueAsBytes(reference),
                Instant.EPOCH, null, callerScope,
            ).jobId
        }!!
    }
}

/** Definitions do not depend on Queue: TaskRegistry -> definitions -> backup service is acyclic. */
@Configuration(proxyBeanMethods = false)
class DataBackupJobTasks(
    private val backup: DataBackupService,
    private val objectMapper: ObjectMapper,
    @Value("\${yona.queue.data-dir:\${yona.data:data}/queue}") dataDirectory: File,
    transactionManager: PlatformTransactionManager,
) {
    private val root = dataDirectory.toPath().toAbsolutePath().normalize()
    private val inputs = root.resolve("import-inputs")
    private val checkpoints = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Bean
    fun dataBackupExportTask() = TaskDefinition(
        type = EXPORT,
        payloadVersion = 1,
        validate = { node ->
            require(node.properties().map { it.key }.toSet() == setOf("project"))
            val project = node["project"]
            require(project.isNull || project.isTextual && validProject(project.textValue()))
        },
        resourceKeys = { listOf(RESOURCE) },
        replaySafe = true,
        handler = { context, payload ->
            val execution = DataBackupExecution(context, checkpoints)
            val project = payload["project"].takeUnless { it.isNull }?.textValue()
            try {
                context.writeArtifact(if (project == null) "yona-site-backup.zip" else "yona-project-backup.zip") { output ->
                    if (project == null) backup.exportSite(output, execution)
                    else backup.exportProject(project.substringBefore('/'), project.substringAfter('/'), output, execution)
                }
                execution.stage("export.complete")
            } catch (_: NoSuchElementException) {
                throw PermanentTaskFailure("The requested project no longer exists.", "BACKUP_PROJECT_NOT_FOUND")
            }
        },
    )

    @Bean
    fun dataBackupImportTask() = TaskDefinition(
        type = IMPORT,
        payloadVersion = 1,
        validate = ::validateReference,
        resourceKeys = { listOf(RESOURCE) },
        replaySafe = false,
        maxAttempts = 1,
        exclusive = true,
        handler = { context, payload ->
            val execution = DataBackupExecution(context, checkpoints)
            try {
                execution.stage("import.verify")
                val path = inputPath(payload["input"].textValue())
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Input is not a regular file" }
                FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                    require(channel.size() == payload["size"].longValue()) { "Input size changed" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    copyArchiveBytes(DigestInputStream(nonClosingInput(channel), digest), OutputStream.nullOutputStream(), execution)
                    require(HexFormat.of().formatHex(digest.digest()) == payload["sha256"].textValue()) { "Input hash changed" }
                    channel.position(0)
                    val scope = readScope(channel, execution)
                    channel.position(0)
                    when (scope) {
                        DataBackupService.SCOPE_SITE -> backup.importSite(Channels.newInputStream(channel), execution)
                        DataBackupService.SCOPE_PROJECT -> backup.importProject(Channels.newInputStream(channel), execution)
                        else -> throw BadBackupArchiveException("Unsupported backup scope")
                    }
                }
                execution.stage("import.complete")
            } catch (failure: Exception) {
                if (failure is StaleAttempt) throw failure
                if (execution.mutationStarted) {
                    throw RecoveryRequiredTaskFailure(
                        "Restore may have changed database rows or files. Inspect the retained input and reconcile before resolving this job.",
                        "BACKUP_RESTORE_UNCERTAIN",
                    )
                }
                if (failure is CooperativeTaskCancellation) throw failure
                throw PermanentTaskFailure("Backup input is invalid, unavailable, or could not be prepared; no restore was started.", "BACKUP_INPUT_REJECTED")
            }
            // Retain even on handler success: only the queue's later fenced commit establishes durable success.
            // Operators may remove this input after inspecting the terminal job; never delete on uncertain completion.
        },
    )

    internal fun stageInput(input: InputStream): Map<String, Any> {
        Files.createDirectories(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Queue data directory must not be a symlink" }
        Files.createDirectories(inputs)
        require(Files.isDirectory(inputs, NOFOLLOW_LINKS)) { "Import input directory must not be a symlink" }
        val name = "${UUID.randomUUID()}.zip"
        val path = inputs.resolve(name)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val size = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW, WRITE).use { output ->
                DigestOutputStream(output, digest).use { input.copyTo(it) }
            }
            FileChannel.open(path, WRITE).use { it.force(true) }
            FileChannel.open(inputs, READ).use { it.force(true) }
            FileChannel.open(root, READ).use { it.force(true) }
            return mapOf("input" to name, "size" to size, "sha256" to HexFormat.of().formatHex(digest.digest()))
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(path) }
            throw failure
        }
    }

    internal fun inputPath(name: String): Path {
        require(INPUT_NAME.matches(name)) { "Invalid input reference" }
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && Files.isDirectory(inputs, NOFOLLOW_LINKS)) { "Invalid input directory" }
        return inputs.resolve(name)
    }

    private fun validateReference(node: JsonNode) {
        require(node.properties().map { it.key }.toSet() == setOf("input", "size", "sha256"))
        require(node["input"].isTextual && INPUT_NAME.matches(node["input"].textValue()))
        require(node["size"].isIntegralNumber && node["size"].canConvertToLong() && node["size"].longValue() >= 0)
        require(node["sha256"].isTextual && SHA256.matches(node["sha256"].textValue()))
    }

    private fun readScope(channel: FileChannel, execution: DataBackupExecution): String? =
        ZipInputStream(nonClosingInput(channel)).use { zip ->
            execution.checkpoint()
            val entry = zip.nextEntry ?: throw BadBackupArchiveException("Missing manifest")
            if (entry.name != "manifest.json" || entry.isDirectory) throw BadBackupArchiveException("Manifest must be first")
            val bytes = zip.readNBytes(65_537)
            require(bytes.size <= 65_536) { "Backup manifest is too large" }
            objectMapper.readTree(bytes)["scope"]?.textValue()
        }

    private fun nonClosingInput(channel: FileChannel): InputStream = object : FilterInputStream(Channels.newInputStream(channel)) {
        override fun close() = Unit
    }

    private fun validProject(value: String): Boolean {
        val segments = value.split('/')
        return segments.size == 2 && segments.all { segment ->
            segment.isNotBlank() && segment.length <= 250 && segment != "." && segment != ".." &&
                segment.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 }
        }
    }

    companion object {
        const val EXPORT = "site.backup-export"
        const val IMPORT = "site.backup-import"
        const val RESOURCE = "site:archive"
        private val INPUT_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.zip")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
