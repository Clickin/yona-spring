package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.YonaApplication
import com.github.yonaprojects.yona.config.Pre2faAuthenticationToken
import com.github.yonaprojects.yona.domain.organization.OrganizationService
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectUser
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.UserService
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.PermanentTaskFailure
import com.github.yonaprojects.yona.queue.DecodedTaskPayload
import com.github.yonaprojects.yona.queue.QueueAttemptToken
import com.github.yonaprojects.yona.queue.QueueCandidateSnapshot
import com.github.yonaprojects.yona.queue.QueueWorkerStore
import com.github.yonaprojects.yona.queue.TaskRegistry
import com.github.yonaprojects.yona.queue.Queue
import com.github.yonaprojects.yona.queue.QueueAdmissionException
import com.github.yonaprojects.yona.queue.QueueClock
import com.github.yonaprojects.yona.queue.TaskDefinition
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Primary
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** Explicitly loaded by the test-only node launcher. No configuration or route is in main sources. */
@TestConfiguration(proxyBeanMethods = false)
class QueueAdminHttpAcceptanceConfiguration {
    @Bean
    @Primary
    @DependsOn("entityManagerFactory")
    fun queueAdminAcceptanceClock(dataSource: DataSource): QueueClock {
        QueueAdminAcceptanceTables.initialize(dataSource)
        return AcceptanceQueueClock(dataSource)
    }

    @Bean
    @Order(0)
    fun queueAdminAcceptanceSecurityChain(http: HttpSecurity): SecurityFilterChain = http
        .securityMatcher("/__test__/queue/v1/**")
        .csrf { it.disable() }
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .build()

    @Bean
    @Primary
    fun queueAdminContendedWorkerStore(
        entityManager: EntityManager,
        transactionManager: PlatformTransactionManager,
        clock: QueueClock,
        registry: TaskRegistry,
        dataSource: DataSource,
        @Value("\${yona.queue.data-dir}") dataDirectory: String,
        @Value("\${yona.queue.lease-millis:60000}") leaseMillis: Long,
        @Value("\${yona.queue.error-summary-codepoints:2048}") errorSummaryCodePoints: Int,
        meterRegistry: MeterRegistry,
        @Value("\${yona.queue.acceptance.control-dir}") control: String,
    ): QueueWorkerStore = HttpContendedQueueWorkerStore(
        entityManager, transactionManager, clock, registry, dataSource, dataDirectory, leaseMillis,
        errorSummaryCodePoints, meterRegistry, Path.of(control),
    )

    @Bean fun httpSuccessTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("success", dataSource, Path.of(control))

    @Bean fun httpRetryOnceTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("retryable-then-success", dataSource, Path.of(control))

    @Bean fun httpPermanentFailureTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("permanent-failure", dataSource, Path.of(control))

    @Bean fun httpGatedCancelTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("gated-cancel", dataSource, Path.of(control))

    @Bean fun httpUnguardedClaimTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("unguarded-claim", dataSource, Path.of(control))

    @Bean fun httpStaleFenceFileTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String): TaskDefinition {
        val base = QueueAcceptanceWorkerTasks.definition("stale-fence-file", dataSource, Path.of(control))
        val handler = checkNotNull(base.handler)
        return base.copy(
            replaySafe = true,
            handler = { context, node ->
                handler(context, node)
                context.fencedDb { entityManager ->
                    entityManager.createNativeQuery("INSERT INTO queue_acceptance_effect(job_id) VALUES (?)")
                        .setParameter(1, context.jobId).executeUpdate()
                }
            },
        )
    }

    @Bean fun httpSuccessPublishTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("success-publish-file", dataSource, Path.of(control))

    @Bean fun httpCrashReplayTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("process-crash-replay", dataSource, Path.of(control))

    @Bean fun httpCrashUnsafeTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("process-crash-unsafe", dataSource, Path.of(control))

    @Bean fun httpRetryBudgetTask(dataSource: DataSource, @Value("\${yona.queue.acceptance.control-dir}") control: String) =
        QueueAcceptanceWorkerTasks.definition("retry-budget-exhaustion", dataSource, Path.of(control))

    @Bean fun httpManualRetryTask() = httpManualTask(permanentAfterRetry = false)
    @Bean fun httpManualRetryPermanentTask() = httpManualTask(permanentAfterRetry = true)

    @Bean fun httpIdempotentTask() = TaskDefinition(
        type = "$HTTP_TASK_PREFIX.idempotent",
        payloadVersion = 1,
        validate = { node -> require(node.properties().all { it.key in setOf("left", "right") }) },
        handler = { context, _ -> context.checkpoint() },
    )

    @Bean fun httpLargeResultTask(dataSource: DataSource): TaskDefinition {
        val jdbc = JdbcTemplate(dataSource)
        return TaskDefinition(
            type = "$HTTP_TASK_PREFIX.large-result", payloadVersion = 1,
            validate = { require(it.properties().all { entry -> entry.key == "resource" } && it["resource"]?.isTextual == true) },
            resourceKeys = { listOf(it["resource"].textValue()!!) },
            handler = { context, _ ->
                context.checkpoint()
                jdbc.update("INSERT INTO queue_acceptance_worker_start(start_id, job_id, attempt_no, owner_instance) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID().toString(), context.jobId, context.attemptNo, context.ownerInstance)
                val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
                context.writeArtifact("large.bin") { output ->
                    repeat(256) { index ->
                        if (index % 16 == 0) context.checkpoint()
                        output.write(chunk)
                    }
                }
                context.progress("다운로드 준비", mapOf("bytes" to 16_777_216L, "files" to 1L))
            },
        )
    }

    private fun httpManualTask(permanentAfterRetry: Boolean) = TaskDefinition(
        type = "$HTTP_TASK_PREFIX.${if (permanentAfterRetry) "manual-retry-permanent" else "manual-retry"}",
        payloadVersion = 1,
        validate = { node ->
            require(node.properties().all { it.key == "resource" })
            require(node["resource"]?.isTextual == true)
        },
        resourceKeys = { node -> listOf(node["resource"].textValue()!!) },
        handler = { context, _ ->
            if (permanentAfterRetry || context.attemptNo == 1L) throw PermanentTaskFailure("HTTP fixture permanent failure")
        },
        replaySafe = false,
        maxAttempts = 5,
        laneLimit = 1,
    )

    companion object {
        const val HTTP_TASK_PREFIX = "queue.acceptance.http"
    }
}

private object QueueAdminAcceptanceTables {
    fun initialize(dataSource: DataSource) {
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE TABLE IF NOT EXISTS queue_test_clock (singleton_id INTEGER PRIMARY KEY, offset_ms BIGINT NOT NULL)")
        if (jdbc.queryForObject("SELECT COUNT(*) FROM queue_test_clock WHERE singleton_id = 1", Int::class.java) == 0) {
            jdbc.update("INSERT INTO queue_test_clock(singleton_id, offset_ms) VALUES (1, 0)")
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS queue_acceptance_worker_start (start_id VARCHAR(36) PRIMARY KEY, job_id BIGINT NOT NULL, attempt_no BIGINT NOT NULL, owner_instance VARCHAR(128) NOT NULL)")
        jdbc.execute("CREATE TABLE IF NOT EXISTS queue_acceptance_resource_activity (resource_key VARCHAR(300) PRIMARY KEY, active_count INTEGER NOT NULL, max_active INTEGER NOT NULL)")
        jdbc.execute("CREATE TABLE IF NOT EXISTS queue_acceptance_effect (job_id BIGINT PRIMARY KEY)")
        jdbc.execute("CREATE TABLE IF NOT EXISTS queue_acceptance_fenced_mutation (request_id VARCHAR(36) PRIMARY KEY, job_id BIGINT NOT NULL, fence BIGINT NOT NULL, value_text VARCHAR(2000) NOT NULL)")
        jdbc.execute("CREATE TABLE IF NOT EXISTS queue_acceptance_business_marker (marker_key VARCHAR(100) PRIMARY KEY)")
    }
}

private class HttpContendedQueueWorkerStore(
    entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    clock: QueueClock,
    registry: TaskRegistry,
    dataSource: DataSource,
    dataDirectory: String,
    leaseMillis: Long,
    errorSummaryCodePoints: Int,
    meterRegistry: MeterRegistry,
    private val control: Path,
) : QueueWorkerStore(
    entityManager, transactionManager, clock, registry, dataSource, dataDirectory,
    leaseMillis, errorSummaryCodePoints, meterRegistry,
) {
    internal override fun claim(
        candidate: QueueCandidateSnapshot,
        definition: TaskDefinition,
        decoded: DecodedTaskPayload,
        ownerInstance: String,
    ): QueueAttemptToken? {
        if (candidate.taskType == QueueAcceptanceWorkerTasks.type("unguarded-claim")) {
            val barrier = control.resolve("claim-barrier").resolve(candidate.id.toString())
            Files.createDirectories(barrier)
            Files.writeString(barrier.resolve(ownerInstance), "rendezvous")
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (Files.list(barrier).use { it.count() } < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            check(Files.list(barrier).use { it.count() } == 2L) { "Both real queue runtimes must reach the DB claim" }
        }
        return super.claim(candidate, definition, decoded, ownerInstance)
    }
}

/** Black-box control and effect observer; queue state is read only through the public REST API. */
@RestController
@RequestMapping("/__test__/queue/v1")
@ConditionalOnProperty(prefix = "yona.queue.acceptance", name = ["enabled"], havingValue = "true")
class QueueAdminHttpAcceptanceHarness(
    private val queue: Queue,
    private val clock: QueueClock,
    private val dataSource: DataSource,
    transactionManager: PlatformTransactionManager,
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val organizationService: OrganizationService,
    private val projectRepository: ProjectRepository,
    private val projectUserRepository: ProjectUserRepository,
    private val roleRepository: RoleRepository,
    private val userDetailsService: UserDetailsService,
    @Value("\${yona.queue.acceptance.control-dir}") controlDirectory: String,
    @Value("\${yona.queue.acceptance.control-token}") controlToken: String,
    @Value("\${server.servlet.session.cookie.name:JSESSIONID}") private val sessionCookieName: String,
    @Value("\${yona.queue.lease-millis:60000}") private val leaseMillis: Long,
) {
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(transactionManager)
    private val control = Path.of(controlDirectory).toAbsolutePath().normalize()
    private val runs = ConcurrentHashMap<String, HttpAcceptanceRun>()
    private val expectedToken = controlToken.toByteArray(Charsets.UTF_8)
    private val mapper = JsonMapper.builder().build()

    init {
        require(expectedToken.size >= 32) { "Test control token must contain at least 256 bits" }
        require(leaseMillis > 0)
        Files.createDirectories(control)
    }

    @GetMapping("/health")
    fun health(
        @RequestHeader("X-Yona-Queue-Test-Control", required = false) token: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, String>> {
        authorize(token, request)
        return ResponseEntity.ok(mapOf("status" to "ready"))
    }

    @GetMapping("/pool")
    fun pool(
        @RequestHeader("X-Yona-Queue-Test-Control", required = false) token: String?,
        request: HttpServletRequest,
    ): Map<String, Int> {
        authorize(token, request)
        val pool = checkNotNull(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource::class.java).hikariPoolMXBean)
        return mapOf("active" to pool.activeConnections, "idle" to pool.idleConnections, "total" to pool.totalConnections)
    }

    @GetMapping("/sse/clock")
    fun sseClock(
        @RequestHeader("X-Yona-Queue-Test-Control", required = false) token: String?,
        request: HttpServletRequest,
    ): Map<String, String> {
        authorize(token, request)
        return mapOf("monotonicNanos" to System.nanoTime().toString())
    }

    @PostMapping("/sessions")
    fun createSession(
        @RequestHeader("X-Yona-Queue-Test-Control", required = false) token: String?,
        @RequestBody body: Map<String, String>,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Map<String, Any> {
        authorize(token, request)
        require(body.keys == setOf("role"))
        val role = body.getValue("role")
        require(role in SESSION_ROLES || isCapacityAdminRole(role))
        val user = fixtureUser(role)
        val principal = userDetailsService.loadUserByUsername(user.loginId)
        val complete = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        val authentication: Authentication = if (role == "pre2fa-admin") Pre2faAuthenticationToken(complete) else complete
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        try {
            val session = request.getSession(true)
            HttpSessionSecurityContextRepository().saveContext(context, request, response)
            return mapOf(
                "role" to role,
                "userId" to checkNotNull(user.id),
                "sessionId" to session.id,
                "cookie" to "$sessionCookieName=${session.id}",
            )
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @PostMapping("/runs")
    fun startScenario(
        @RequestHeader("X-Yona-Queue-Test-Control", required = false) token: String?,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        authorize(token, request)
        if (!validRunBody(body)) return ResponseEntity.badRequest().body(mapOf("error" to "INVALID_SCENARIO"))
        val scenario = body.getValue("scenario") as String
        val runId = UUID.randomUUID().toString()
        val session = request.getSession(false)
        val userId = SecurityContextHolder.getContext().authentication?.name?.let { loginId ->
            userRepository.findByLoginId(loginId).orElse(null)?.id
        }
        val run = HttpAcceptanceRun(runId, scenario, session, userId)
        runs[runId] = run
        return try {
            val jobIds = when (scenario) {
                "enqueue-commit", "enqueue-rollback" -> enqueueBusinessScenario(body, scenario, run)
                else -> enqueueScenario(body, scenario, run)
            }
            run.jobIds += jobIds
            val outcome = when (scenario) {
                "enqueue-rollback" -> "ROLLED_BACK"
                else -> "COMMITTED"
            }
            val result = linkedMapOf<String, Any>(
                "runId" to runId,
                "enqueueOutcome" to outcome,
                "jobIds" to jobIds.map(Long::toString),
            )
            if (scenario in setOf("enqueue-commit", "enqueue-rollback")) {
                result["committedBusinessMarker"] = businessMarkerExists(body.getValue("businessMarker").toString())
            }
            ResponseEntity.ok(result)
        } catch (failure: QueueAdmissionException) {
            if (failure.code != "IDEMPOTENCY_CONFLICT") throw failure
            ResponseEntity.ok(mapOf(
                "runId" to runId,
                "enqueueOutcome" to "IDEMPOTENCY_CONFLICT",
                "jobIds" to emptyList<String>(),
            ))
        } catch (_: IllegalArgumentException) {
            runs.remove(runId)
            ResponseEntity.badRequest().body(mapOf("error" to "INVALID_SCENARIO"))
        }
    }

    @GetMapping("/runs/{runId}")
    fun observeRun(
        @RequestHeader("X-Yona-Queue-Test-Control", required = false) token: String?,
        @PathVariable runId: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        authorize(token, request)
        val run = runs[runId] ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build<Any>()
        val jobs = run.jobIds
        val jobParams = jobs.toTypedArray()
        if (run.scenario == "simultaneous-claim" || run.scenario == "same-resource-pair") awaitWorkerStart(run)
        val starts = if (jobs.isEmpty()) 0 else jdbc.queryForObject(
            "SELECT COUNT(*) FROM queue_acceptance_worker_start WHERE job_id IN (${placeholders(jobs.size)})",
            Int::class.java,
            *jobParams,
        )!!
        val auditCount = if (jobs.isEmpty()) 0 else jdbc.queryForObject(
            "SELECT COUNT(*) FROM queue_admin_audit WHERE job_id IN (${placeholders(jobs.size)})",
            Int::class.java,
            *jobParams,
        )!!
        val effects = if (jobs.isEmpty()) emptyList() else buildList {
            jdbc.query(
                "SELECT request_id FROM queue_acceptance_fenced_mutation WHERE job_id IN (${placeholders(jobs.size)})",
                { row, _ -> add("db:${row.getString(1)}") },
                *jobParams,
            )
            jdbc.query(
                "SELECT job_id FROM queue_acceptance_effect WHERE job_id IN (${placeholders(jobs.size)})",
                { row, _ -> add("effect:${row.getLong(1)}") },
                *jobParams,
            )
        }
        val effectCount = effects.size
        val effectDigest = jobs.firstNotNullOfOrNull { jobId ->
            jdbc.query(
                "SELECT sha256 FROM queue_artifact WHERE job_id = ? ORDER BY attempt_no DESC",
                { row, _ -> row.getString(1) },
                jobId,
            ).firstOrNull()
        }
        val maxConcurrent = run.resourceKey?.let { resource ->
            jdbc.queryForObject(
                "SELECT max_active FROM queue_acceptance_resource_activity WHERE resource_key = ?",
                Int::class.java,
                resource,
            )
        } ?: 0
        val gateOpen = run.jobIds.any { jobId ->
            val gate = control.resolve("gates").resolve(jobId.toString())
            Files.isDirectory(gate) && Files.list(gate).use { paths ->
                paths.anyMatch { it.fileName.toString().endsWith(".entered") }
            } && !run.paused.get() && !Files.exists(gate.resolve("release"))
        }
        val artifactEffects = if (jobs.isEmpty()) emptyList() else jdbc.query(
            "SELECT relative_path FROM queue_artifact WHERE job_id IN (${placeholders(jobs.size)})",
            { row, _ -> "artifact:${row.getString(1)}" },
            *jobParams,
        )
        return ResponseEntity.ok(mapOf(
            "runId" to runId,
            "taskStarts" to starts,
            "maxConcurrentForResource" to maxConcurrent,
            "gateOpen" to gateOpen,
            "oldFenceWritesAccepted" to run.oldWritesAccepted.get(),
            "committedEffectCount" to effectCount,
            "effectSha256" to effectDigest,
            "effects" to (effects + artifactEffects).take(100),
            "adminAuditCount" to auditCount,
        ))
    }

    @PostMapping("/runs/{runId}/actions/{action}")
    fun controlRun(
        @RequestHeader("X-Yona-Queue-Test-Control", required = false) token: String?,
        @PathVariable runId: String,
        @PathVariable action: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        authorize(token, request)
        val run = runs[runId] ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val startedAt = System.nanoTime()
        val accepted = when (action) {
            "release-gate" -> { run.jobIds.forEach(::releaseGate); true }
            "pause-old-attempt" -> {
                val jobId = run.jobIds.singleOrNull() ?: return conflict()
                waitForGate(jobId)
                run.paused.set(true)
                true
            }
            "wait-for-lease-expiry" -> waitForLeaseExpiry(run)
            "resume-old-attempt" -> probeOldAttempt(run)
            "revoke-test-manager" -> changeRunUserState(run, UserState.ACTIVE)
            "restore-test-manager" -> changeRunUserState(run, UserState.SITE_ADMIN)
            "disable-test-manager" -> changeRunUserState(run, UserState.DELETED)
            "restore-disabled-test-manager" -> changeRunUserState(run, UserState.SITE_ADMIN)
            "invalidate-test-manager-session" -> invalidateRunSession(run)
            else -> return conflict()
        }
        return if (accepted) ResponseEntity.noContent()
            .header("X-Yona-Queue-Test-Action-Started-Monotonic-Nanos", startedAt.toString())
            .header("X-Yona-Queue-Test-Commit-Monotonic-Nanos", System.nanoTime().toString())
            .build() else conflict()
    }

    private fun enqueueScenario(body: Map<String, Any?>, scenario: String, run: HttpAcceptanceRun): List<Long> {
        val resource = normalizedResource(body["resourceKey"]?.toString() ?: body.getValue("runKey").toString())
        run.resourceKey = resource
        ensureActivityRow(resource)
        val version = (body["payloadVersion"] as? Number)?.toInt() ?: 1
        val dueAt = if (scenario == "delayed") {
            Instant.ofEpochMilli(clock.now()).plusMillis((body["delayMs"] as Number).toLong())
        } else {
            Instant.EPOCH
        }
        if (scenario == "large-id") {
            val current = jdbc.queryForObject(
                "SELECT counter_value FROM queue_meta WHERE counter_name = 'next-id'",
                Long::class.javaObjectType,
            )!!
            if (current < SAFE_JSON_INTEGER + 10) jdbc.update(
                "UPDATE queue_meta SET counter_value = ? WHERE counter_name = 'next-id'",
                SAFE_JSON_INTEGER + 10,
            )
        }
        val spec = when (scenario) {
            "unsupported-type" -> EnqueueSpec("queue.acceptance.http.not-registered", version, workerPayload(resource))
            "unsupported-payload-version" -> EnqueueSpec(QueueAcceptanceWorkerTasks.type("success"), version, workerPayload(resource))
            "idempotent" -> {
                val encoded = body.getValue("payloadBytesBase64") as String
                val bytes = Base64.getDecoder().decode(encoded)
                require(Base64.getEncoder().encodeToString(bytes) == encoded && bytes.size <= MAX_PAYLOAD_BYTES)
                EnqueueSpec(
                    "${QueueAdminHttpAcceptanceConfiguration.HTTP_TASK_PREFIX}.idempotent",
                    version,
                    bytes,
                    body["idempotencyKey"]?.toString(),
                )
            }
            "manual-retry" -> EnqueueSpec(
                "${QueueAdminHttpAcceptanceConfiguration.HTTP_TASK_PREFIX}.manual-retry", 1,
                mapper.writeValueAsBytes(mapOf("resource" to resource)),
            )
            "manual-retry-permanent" -> EnqueueSpec(
                "${QueueAdminHttpAcceptanceConfiguration.HTTP_TASK_PREFIX}.manual-retry-permanent", 1,
                mapper.writeValueAsBytes(mapOf("resource" to resource)),
            )
            "large-result" -> EnqueueSpec(
                "${QueueAdminHttpAcceptanceConfiguration.HTTP_TASK_PREFIX}.large-result", 1,
                mapper.writeValueAsBytes(mapOf("resource" to resource)),
            )
            else -> {
                val workerScenario = when (scenario) {
                    "simultaneous-claim" -> "unguarded-claim"
                    "retry-once" -> "retryable-then-success"
                    "result-blob" -> "success-publish-file"
                    "process-crash-replay" -> "process-crash-replay"
                    "process-crash-unsafe" -> "process-crash-unsafe"
                    "stale-fence-file" -> "stale-fence-file"
                    "permanent-failure" -> "permanent-failure"
                    "gated-cancel", "same-resource-pair" -> "gated-cancel"
                    else -> "success"
                }
                val bytes = if (scenario == "result-blob") "queue-result-${run.id}".toByteArray(Charsets.UTF_8) else byteArrayOf()
                val publication = if (scenario == "result-blob") "results/${run.id}.bin" else null
                EnqueueSpec(QueueAcceptanceWorkerTasks.type(workerScenario), 1, workerPayload(resource, publication, bytes))
            }
        }
        if (scenario == "same-resource-pair") {
            return listOf(
                committedJob(QueueAcceptanceWorkerTasks.type("gated-cancel"), 1, spec.payload, null, dueAt),
                committedJob(QueueAcceptanceWorkerTasks.type("success"), 1, workerPayload(resource), null, dueAt),
            )
        }
        return listOf(committedJob(spec.type, spec.version, spec.payload, spec.idempotencyKey, dueAt))
    }
    private fun enqueueBusinessScenario(body: Map<String, Any?>, scenario: String, run: HttpAcceptanceRun): List<Long> {
        val marker = body.getValue("businessMarker").toString()
        val resource = normalizedResource(marker)
        run.resourceKey = resource
        ensureActivityRow(resource)
        val jobId = try {
            transactions.execute {
                jdbc.update("INSERT INTO queue_acceptance_business_marker(marker_key) VALUES (?)", marker)
                val receipt = queue.enqueue(
                    QueueAcceptanceWorkerTasks.type("success"), 1,
                    workerPayload(resource), Instant.EPOCH, null, "queue-http-business",
                )
                if (scenario == "enqueue-rollback") throw IntentionalFixtureRollback()
                receipt.jobId
            }
        } catch (_: IntentionalFixtureRollback) {
            null
        }
        return listOfNotNull(jobId)
    }

    private fun committedJob(type: String, version: Int, payload: ByteArray, key: String?, dueAt: Instant): Long =
        queue.enqueue(type, version, payload, dueAt, key, "queue-http-acceptance").jobId

    private fun workerPayload(resource: String, publicationPath: String? = null, bytes: ByteArray = byteArrayOf()) =
        QueueAcceptanceWorkerTasks.payload(resource, publicationPath, bytes)

    private fun ensureActivityRow(resource: String) {
        val exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM queue_acceptance_resource_activity WHERE resource_key = ?",
            Int::class.java,
            resource,
        )!! > 0
        if (!exists) jdbc.update(
            "INSERT INTO queue_acceptance_resource_activity(resource_key, active_count, max_active) VALUES (?, 0, 0)",
            resource,
        )
    }

    private fun awaitWorkerStart(run: HttpAcceptanceRun) {
        val resource = checkNotNull(run.resourceKey)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val ids = run.jobIds
            val starts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM queue_acceptance_worker_start WHERE job_id IN (${placeholders(ids.size)})",
                Int::class.java,
                *ids.toTypedArray(),
            )!!
            val maxActive = jdbc.queryForObject(
                "SELECT max_active FROM queue_acceptance_resource_activity WHERE resource_key = ?",
                Int::class.java,
                resource,
            )!!
            if (starts > 0 && maxActive > 0) return
            Thread.sleep(10)
        }
        error("Real worker did not publish its independent start observation")
    }


    private fun waitForLeaseExpiry(run: HttpAcceptanceRun): Boolean {
        val jobId = run.jobIds.singleOrNull() ?: return false
        if (!run.paused.get() || !run.clockAdvanced.compareAndSet(false, true)) return false
        val gate = control.resolve("gates").resolve(jobId.toString())
        val entered = Files.list(gate).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".entered") }.findFirst().orElse(null)
        } ?: return false
        val owner = Files.readString(entered)
        val probe = control.resolve("probes").resolve(owner).resolve(jobId.toString())
        Files.createDirectories(probe)
        jdbc.update("UPDATE queue_test_clock SET offset_ms = offset_ms + ? WHERE singleton_id = 1", leaseMillis + 2_000)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val result = sendProbe(probe, "file", listOf(
                Base64.getEncoder().encodeToString("stale-probe/${UUID.randomUUID()}.bin".toByteArray(Charsets.UTF_8)),
                Base64.getEncoder().encodeToString("unpublished-probe".toByteArray(Charsets.UTF_8)),
            ))
            if (result == "REJECTED_STALE_OWNER") return true
            if (result == null || result.startsWith("ERROR:")) return false
            Thread.sleep(20)
        }
        return false
    }

    private fun probeOldAttempt(run: HttpAcceptanceRun): Boolean {
        val jobId = run.jobIds.singleOrNull() ?: return false
        if (!run.paused.get()) return false
        val gate = control.resolve("gates").resolve(jobId.toString())
        val entered = Files.list(gate).use { paths -> paths.filter { it.fileName.toString().endsWith(".entered") }.findFirst().orElse(null) }
            ?: return false
        val owner = Files.readString(entered)
        val probe = control.resolve("probes").resolve(owner).resolve(jobId.toString())
        Files.createDirectories(probe)
        val dbResult = sendProbe(probe, "db", listOf(Base64.getEncoder().encodeToString("stale-owner".toByteArray(Charsets.UTF_8))))
        val fileResult = sendProbe(probe, "file", listOf(
            Base64.getEncoder().encodeToString("stale/$jobId.bin".toByteArray(Charsets.UTF_8)),
            Base64.getEncoder().encodeToString("stale-file".toByteArray(Charsets.UTF_8)),
        ))
        run.oldWritesAccepted.set(dbResult == "ACCEPTED" || fileResult == "ACCEPTED")
        return dbResult == "REJECTED_STALE_OWNER" && fileResult == "REJECTED_STALE_OWNER"
    }

    private fun sendProbe(directory: Path, operation: String, fields: List<String>): String? {
        val requestId = UUID.randomUUID().toString()
        val request = directory.resolve("$requestId.request")
        val response = directory.resolve("$requestId.response")
        Files.writeString(request, (listOf(requestId, operation) + fields).joinToString("\n"))
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && !Files.exists(response)) Thread.sleep(10)
        if (!Files.exists(response)) return null
        val result = Files.readString(response).split('\n').getOrNull(4)
        Files.deleteIfExists(response)
        return result
    }

    private fun waitForGate(jobId: Long) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        val gate = control.resolve("gates").resolve(jobId.toString())
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(gate) && Files.list(gate).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".entered") } }) return
            Thread.sleep(10)
        }
        throw IllegalStateException("Real worker did not enter the gate")
    }

    private fun releaseGate(jobId: Long) {
        val gate = control.resolve("gates").resolve(jobId.toString())
        Files.createDirectories(gate)
        Files.writeString(gate.resolve("release"), "release")
    }

    private fun changeRunUserState(run: HttpAcceptanceRun, state: UserState): Boolean {
        val id = run.userId ?: return false
        val user = userRepository.findById(id).orElse(null) ?: return false
        user.state = state
        userRepository.save(user)
        return true
    }

    private fun invalidateRunSession(run: HttpAcceptanceRun): Boolean {
        val session = run.session ?: return false
        return try {
            session.invalidate()
            true
        } catch (_: IllegalStateException) {
            true
        }
    }

    private fun isCapacityAdminRole(role: String): Boolean =
        role.startsWith("capacity-admin-") && role.removePrefix("capacity-admin-").toIntOrNull() in 0..33

    private fun fixtureUser(role: String): User {
        val login = "queue-http-${role.replace('-', '_')}"
        val initialState = if (role in ADMIN_ROLES || isCapacityAdminRole(role)) UserState.SITE_ADMIN else UserState.ACTIVE
        val user = userRepository.findByLoginId(login).orElseGet {
            userService.createUser(User(
                loginId = login,
                name = "Queue HTTP $role",
                email = "$login@example.invalid",
                state = initialState,
            ))
        }
        if (user.state != initialState) {
            user.state = initialState
            userRepository.save(user)
        }
        if (role == "org-admin") ensureOrganizationAndProjectAdmin(user)
        return user
    }

    private fun ensureOrganizationAndProjectAdmin(user: User) {
        val orgName = "queue-http-${user.loginId}"
        if (organizationService.findByName(orgName) == null) {
            organizationService.createOrganization(orgName, "Queue HTTP fixture", checkNotNull(user.id))
        }
        val projectName = "queue-http-${user.loginId.replace('_', '-') }"
        val project = projectRepository.findByOwnerAndName(user.loginId, projectName).orElseGet {
            projectRepository.save(Project(name = projectName, owner = user.loginId))
        }
        if (!projectUserRepository.existsByProjectIdAndUserLoginId(checkNotNull(project.id), user.loginId)) {
            val manager = roleRepository.findById(RoleType.MANAGER.roleType).orElseThrow()
            projectUserRepository.save(ProjectUser(user = user, project = project, role = manager))
        }
    }

    private fun businessMarkerExists(marker: String): Boolean = jdbc.queryForObject(
        "SELECT COUNT(*) FROM queue_acceptance_business_marker WHERE marker_key = ?",
        Int::class.java,
        marker,
    )!! > 0

    private fun validRunBody(body: Map<String, Any?>): Boolean {
        val allowed = setOf(
            "scenario", "runKey", "resourceKey", "otherTaskType", "delayMs", "payloadVersion",
            "idempotencyKey", "payloadBytesBase64", "businessMarker", "rollback",
        )
        val scenarios = setOf(
            "success", "retry-once", "manual-retry", "manual-retry-permanent", "permanent-failure",
            "gated-cancel", "delayed", "simultaneous-claim", "process-crash-replay", "unsupported-type",
            "unsupported-payload-version", "idempotent", "same-resource-pair", "stale-fence-file", "result-blob",
            "enqueue-commit", "enqueue-rollback", "large-id", "large-result",
            "process-crash-unsafe",
        )
        val scenario = body["scenario"] as? String ?: return false
        val runKey = body["runKey"] as? String ?: return false
        val resourceKey = body["resourceKey"] as? String
        val delay = (body["delayMs"] as? Number)?.toLong()
        val payloadVersion = (body["payloadVersion"] as? Number)?.toInt()
        val marker = body["businessMarker"] as? String
        val payload = body["payloadBytesBase64"] as? String
        val idempotencyKey = body["idempotencyKey"] as? String
        val otherTaskType = body["otherTaskType"] as? String
        val rollback = body["rollback"] as? Boolean
        return body.keys.all(allowed::contains) && scenario in scenarios &&
            runKey.length in 8..100 &&
            (body["resourceKey"] == null || (resourceKey != null && resourceKey.length in 1..200)) &&
            (body["delayMs"] == null || (delay != null && delay in 1_000L..60_000L)) &&
            (body["payloadVersion"] == null || (payloadVersion != null && payloadVersion in 1..1_000)) &&
            (body["businessMarker"] == null || (marker != null && marker.length in 1..100)) &&
            (body["payloadBytesBase64"] == null || (payload != null && payload.length <= 1_398_104)) &&
            (body["idempotencyKey"] == null || (idempotencyKey != null && idempotencyKey.length <= 200)) &&
            (body["otherTaskType"] == null || (otherTaskType != null && otherTaskType.length <= 80)) &&
            (body["rollback"] == null || rollback != null) &&
            (scenario != "idempotent" || payload != null) &&
            (payload == null || scenario == "idempotent") &&
            (scenario !in setOf("enqueue-commit", "enqueue-rollback") || marker != null) &&
            (scenario != "delayed" || delay != null)
    }

    private fun authorize(token: String?, request: HttpServletRequest) {
        val address = runCatching { InetAddress.getByName(request.remoteAddr) }.getOrNull()
        if (address?.isLoopbackAddress != true || token == null ||
            !MessageDigest.isEqual(expectedToken, token.toByteArray(Charsets.UTF_8))) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    private fun normalizedResource(value: String): String {
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
        return "test:${digest.take(40)}"
    }

    private fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")

    private fun conflict() = ResponseEntity.status(HttpStatus.CONFLICT).build<Void>()

    private data class EnqueueSpec(val type: String, val version: Int, val payload: ByteArray, val idempotencyKey: String? = null)

    private class IntentionalFixtureRollback : RuntimeException()

    companion object {
        private const val SAFE_JSON_INTEGER = 9_007_199_254_740_991L
        private const val MAX_PAYLOAD_BYTES = 1_048_576
        private val ADMIN_ROLES = setOf(
            "admin", "pre2fa-admin", "revoke-admin", "disabled-admin", "session-admin", "node-two-admin",
        )
        private val SESSION_ROLES = setOf(
            "admin", "member", "org-admin", "pre2fa-admin", "revoke-admin", "disabled-admin", "session-admin", "node-two-admin",
        )
    }
}

private data class HttpAcceptanceRun(
    val id: String,
    val scenario: String,
    val session: HttpSession?,
    val userId: Long?,
    val jobIds: MutableList<Long> = java.util.concurrent.CopyOnWriteArrayList(),
    val paused: AtomicBoolean = AtomicBoolean(),
    val oldWritesAccepted: AtomicBoolean = AtomicBoolean(),
    val clockAdvanced: AtomicBoolean = AtomicBoolean(),
) {
    @Volatile var resourceKey: String? = null
}

object QueueAdminHttpAcceptanceNodeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val application = org.springframework.boot.builder.SpringApplicationBuilder(
            YonaApplication::class.java,
            QueueAdminHttpAcceptanceConfiguration::class.java,
            QueueSseHttpAcceptanceConfiguration::class.java,
        ).web(org.springframework.boot.WebApplicationType.SERVLET).run(*args)
        if (application.getBean(UserRepository::class.java).count() == 0L) {
            application.getBean(UserService::class.java).createUser(User(
                loginId = "queue-http-bootstrap", name = "Queue fixture bootstrap",
                email = "queue-http-bootstrap@example.invalid", state = UserState.SITE_ADMIN,
            ))
        }
        System.out.println("QUEUE_ADMIN_HTTP_READY:${application.environment.getRequiredProperty("local.server.port")}")
        System.out.flush()
        java.util.concurrent.CountDownLatch(1).await()
    }
}
