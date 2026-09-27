package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.queue.PermanentTaskFailure
import com.github.yonaprojects.yona.queue.RetryableTaskFailure
import com.github.yonaprojects.yona.queue.StaleAttempt
import com.github.yonaprojects.yona.queue.TaskContext
import com.github.yonaprojects.yona.queue.TaskDefinition
import com.github.yonaprojects.yona.queue.DecodedTaskPayload
import com.github.yonaprojects.yona.queue.QueueAttemptToken
import com.github.yonaprojects.yona.queue.QueueCandidateSnapshot
import com.github.yonaprojects.yona.queue.QueueClock
import com.github.yonaprojects.yona.queue.QueueWorkerStore
import com.github.yonaprojects.yona.queue.TaskRegistry
import jakarta.persistence.EntityManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import javax.sql.DataSource

/** Uses the production DB-time query and adds only the fixture-owned, shared test offset. */
class AcceptanceQueueClock(dataSource: DataSource) : com.github.yonaprojects.yona.queue.QueueClock(dataSource) {
    private val jdbc = JdbcTemplate(dataSource)
    override fun now(): Long = super.now() + offset()
    override fun leaseNow(): Long = super.leaseNow() + offset()

    private fun offset(): Long = jdbc.queryForObject(
        "SELECT offset_ms FROM queue_test_clock WHERE singleton_id = 1", Long::class.javaObjectType,
    )!!
}

/** Explicitly loaded in worker child JVMs before QueueWorkerRuntime's SmartLifecycle starts. */
@TestConfiguration(proxyBeanMethods = false)
class QueueAcceptanceWorkerProcessConfiguration {
    @Bean @Primary
    fun acceptanceQueueClock(dataSource: DataSource) = AcceptanceQueueClock(dataSource)

    @Bean @Primary
    fun acceptanceWorkerStore(
        entityManager: EntityManager, transactions: PlatformTransactionManager, clock: QueueClock,
        registry: TaskRegistry, dataSource: DataSource,
        @Value("\${yona.queue.data-dir}") dataDirectory: String,
        @Value("\${yona.queue.acceptance.control-dir}") control: String,
        @Value("\${yona.queue.lease-millis}") leaseMillis: Long,
    ): QueueWorkerStore = ContendedQueueWorkerStore(
        entityManager, transactions, clock, registry, dataSource, dataDirectory, leaseMillis, Path.of(control),
    )

    @Bean fun unguardedClaimTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("unguarded-claim", dataSource, Path.of(control))

    @Bean fun simultaneousClaimTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("simultaneous-claim", dataSource, Path.of(control))
    @Bean fun retryableTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("retryable-then-success", dataSource, Path.of(control))
    @Bean fun permanentTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("permanent-failure", dataSource, Path.of(control))
    @Bean fun cancelTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("gated-cancel", dataSource, Path.of(control))
    @Bean fun unsafeCancelTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("unsafe-gated-cancel", dataSource, Path.of(control))
    @Bean fun staleFileTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("stale-fence-file", dataSource, Path.of(control))
    @Bean fun successPublishTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("success-publish-file", dataSource, Path.of(control))
    @Bean fun successTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("success", dataSource, Path.of(control))
    @Bean fun safeCrashTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("process-crash-replay", dataSource, Path.of(control))
    @Bean fun unsafeCrashTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("process-crash-unsafe", dataSource, Path.of(control))
    @Bean fun retryBudgetTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("retry-budget-exhaustion", dataSource, Path.of(control))
}

private class ContendedQueueWorkerStore(
    entityManager: EntityManager, transactions: PlatformTransactionManager, clock: QueueClock,
    registry: TaskRegistry, dataSource: DataSource, dataDirectory: String, leaseMillis: Long,
    private val control: Path,
) : QueueWorkerStore(entityManager, transactions, clock, registry, dataSource, dataDirectory, leaseMillis) {
    internal override fun claim(
        candidate: QueueCandidateSnapshot, definition: TaskDefinition,
        decoded: DecodedTaskPayload, ownerInstance: String,
    ): QueueAttemptToken? {
        if (candidate.taskType == QueueAcceptanceWorkerTasks.type("unguarded-claim")) {
            check(candidate.resourceKeys.isEmpty())
            val barrier = control.resolve("claim-barrier").resolve(candidate.id.toString())
            Files.createDirectories(barrier)
            Files.writeString(barrier.resolve(ownerInstance), "entering production database claim")
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (Files.list(barrier).use { it.count() } < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            check(Files.list(barrier).use { it.count() } == 2L) { "Two real DB contenders did not rendezvous" }
        }
        return super.claim(candidate, definition, decoded, ownerInstance)
    }
}

internal object QueueAcceptanceWorkerTasks {
    private val mapper = JsonMapper.builder().build()
    private const val BASE_TYPE = "queue.acceptance.worker"

    fun type(scenario: String): String = "$BASE_TYPE.$scenario"

    fun definition(scenario: String, dataSource: DataSource, control: Path): TaskDefinition {
        val jdbc = JdbcTemplate(dataSource)
        return TaskDefinition(
            type = type(scenario),
            payloadVersion = 1,
            validate = { node ->
                require(node.properties().all { it.key in setOf("resource", "publicationPath", "payloadBase64") })
                require(node["resource"]?.isTextual == true)
                require(node["publicationPath"] == null || node["publicationPath"].isTextual)
                require(node["payloadBase64"] == null || node["payloadBase64"].isTextual)
            },
            resourceKeys = { node -> if (scenario == "unguarded-claim") emptyList() else listOf(node["resource"].textValue()!!) },
            handler = { context, node -> execute(scenario, context, node, jdbc, dataSource, control) },
            replaySafe = scenario == "process-crash-replay" || scenario == "retry-budget-exhaustion",
            maxAttempts = 5,
            laneLimit = 1,
        )
    }

    fun definitions(dataSource: DataSource, control: Path): List<TaskDefinition> = listOf(
        "simultaneous-claim", "retryable-then-success", "permanent-failure", "gated-cancel",
        "unsafe-gated-cancel", "stale-fence-file", "success-publish-file", "success",
        "process-crash-replay", "process-crash-unsafe", "retry-budget-exhaustion",
        "unguarded-claim",
    ).map { definition(it, dataSource, control) }

    fun payload(resource: String, publicationPath: String?, bytes: ByteArray): ByteArray = mapper.writeValueAsBytes(
        buildMap {
            put("resource", resource)
            if (publicationPath != null) put("publicationPath", publicationPath)
            if (bytes.isNotEmpty() || publicationPath != null) put("payloadBase64", Base64.getEncoder().encodeToString(bytes))
        },
    )

    private fun execute(
        scenario: String, context: TaskContext, node: JsonNode, jdbc: JdbcTemplate, dataSource: DataSource, control: Path,
    ) {
        // A new physical connection cannot observe an uncommitted claim on the invoking thread.
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM queue_job j JOIN queue_attempt a ON a.job_id = j.id " +
                    "AND a.attempt_no = j.active_attempt_no WHERE j.id = ? AND a.attempt_no = ? " +
                    "AND a.fence = ? AND a.owner_instance = ? AND a.outcome = 'RUNNING'",
            ).use { statement ->
                statement.queryTimeout = 5
                statement.setLong(1, context.jobId)
                statement.setLong(2, context.attemptNo)
                statement.setLong(3, context.fence)
                statement.setString(4, context.ownerInstance)
                statement.executeQuery().use { row ->
                    check(row.next() && row.getInt(1) == 1) { "Handler started before its attempt was durable" }
                }
            }
        }
        val resource = node["resource"].textValue()!!
        jdbc.update(
            "INSERT INTO queue_acceptance_worker_start(start_id, job_id, attempt_no, owner_instance) VALUES (?, ?, ?, ?)",
            java.util.UUID.randomUUID().toString(), context.jobId, context.attemptNo, context.ownerInstance,
        )
        jdbc.update(
            "UPDATE queue_acceptance_resource_activity " +
                "SET max_active = CASE WHEN active_count + 1 > max_active THEN active_count + 1 ELSE max_active END, " +
                "active_count = active_count + 1 " +
                "WHERE resource_key = ?",
            resource,
        ).also { check(it == 1) { "Missing acceptance activity row for $resource" } }

        try {
            when (scenario) {
                "simultaneous-claim", "unguarded-claim", "gated-cancel", "unsafe-gated-cancel", "stale-fence-file" -> awaitGate(context, control)
                "retryable-then-success" -> {
                    if (context.attemptNo == 1L) throw RetryableTaskFailure("acceptance retry")
                }
                "permanent-failure" -> throw PermanentTaskFailure("acceptance permanent failure")
                "success-publish-file" -> publish(context, node)
                "process-crash-replay" -> {
                    recordEffectOnce(context)
                    if (context.attemptNo == 1L) awaitGate(context, control)
                    context.checkpoint()
                }
                "process-crash-unsafe" -> {
                    recordEffectOnce(context)
                    awaitGate(context, control)
                }
                "retry-budget-exhaustion" -> throw RetryableTaskFailure("acceptance retry budget")
                "success" -> context.checkpoint()
                else -> error("Unregistered acceptance handler: $scenario")
            }
        } finally {
            jdbc.update(
                "UPDATE queue_acceptance_resource_activity SET active_count = active_count - 1 " +
                    "WHERE resource_key = ? AND active_count > 0", resource,
            )
            val returned = control.resolve("returns").resolve(context.ownerInstance)
            Files.createDirectories(returned)
            Files.writeString(returned.resolve("${context.jobId}-${context.attemptNo}.returned"), "returned")
        }
    }

    private fun publish(context: TaskContext, node: JsonNode) {
        context.checkpoint()
        val relativePath = node["publicationPath"].textValue()!!
        val bytes = Base64.getDecoder().decode(node["payloadBase64"].textValue()!!)
        context.writeArtifact(relativePath) { output -> output.write(bytes) }
    }

    private fun awaitGate(context: TaskContext, control: Path) {
        val job = control.resolve("gates").resolve(context.jobId.toString())
        Files.createDirectories(job)
        Files.writeString(job.resolve("${context.attemptNo}.entered"), context.ownerInstance)
        val probeDirectory = control.resolve("probes").resolve(context.ownerInstance).resolve(context.jobId.toString())
        // Keep the task thread and physical guard alive until the test explicitly releases it.
        while (!Files.exists(job.resolve("release"))) {
            handleProbeRequests(context, probeDirectory)
            Thread.sleep(10)
        }
        handleProbeRequests(context, probeDirectory)
        context.checkpoint()
    }

    private fun handleProbeRequests(context: TaskContext, directory: Path) {
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { requests ->
            requests.filter { it.fileName.toString().endsWith(".request") }.forEach { request ->
                val fields = Files.readString(request).split('\n')
                val requestId = fields[0]
                var result = "ACCEPTED"
                try {
                    when (fields[1]) {
                        "db" -> {
                            val value = String(Base64.getDecoder().decode(fields[2]), Charsets.UTF_8)
                            context.fencedDb { entityManager ->
                                entityManager.createNativeQuery(
                                    "INSERT INTO queue_acceptance_fenced_mutation(request_id, job_id, fence, value_text) " +
                                        "VALUES (?, ?, ?, ?)",
                                ).setParameter(1, requestId).setParameter(2, context.jobId)
                                    .setParameter(3, context.fence).setParameter(4, value).executeUpdate()
                            }
                        }
                        "file" -> {
                            val relativePath = String(Base64.getDecoder().decode(fields[2]), Charsets.UTF_8)
                            val bytes = Base64.getDecoder().decode(fields[3])
                            context.writeArtifact(relativePath) { output -> output.write(bytes) }
                            context.checkpoint()
                        }
                        else -> error("Unknown probe command")
                    }
                } catch (_: StaleAttempt) {
                    result = "REJECTED_STALE_OWNER"
                } catch (failure: Exception) {
                    result = "ERROR:${failure.javaClass.name}:${failure.message ?: ""}"
                } finally {
                    Files.deleteIfExists(request)
                }
                val response = directory.resolve("$requestId.response")
                writeAtomically(response, listOf(
                    requestId, context.ownerInstance, context.jobId.toString(), context.fence.toString(), result,
                ).joinToString("\n"))
            }
        }
    }

    private fun recordEffectOnce(context: TaskContext) {
        context.fencedDb { entityManager ->
            val count = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM queue_acceptance_effect WHERE job_id = ?",
                Long::class.javaObjectType,
            ).setParameter(1, context.jobId).singleResult
            if (count == 0L) entityManager.createNativeQuery(
                "INSERT INTO queue_acceptance_effect(job_id) VALUES (?)",
            ).setParameter(1, context.jobId).executeUpdate()
        }
    }

    private fun writeAtomically(target: Path, value: String) {
        Files.createDirectories(target.parent)
        val staging = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(staging, value)
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
