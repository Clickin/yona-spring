package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.config.SchemaTestDatabase
import com.github.yonaprojects.yona.config.SchedulerConfig
import com.github.yonaprojects.yona.domain.mail.MailServiceImpl
import com.github.yonaprojects.yona.domain.support.DatabaseInitializer
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserService
import com.github.yonaprojects.yona.domain.user.UserServiceImpl
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.Queue
import com.github.yonaprojects.yona.queue.QueueClock
import com.github.yonaprojects.yona.queue.TaskDefinition
import com.github.yonaprojects.yona.queue.TaskRegistry
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.opentest4j.TestAbortedException

@TestConfiguration(proxyBeanMethods = false)
class QueueAcceptanceFixtureTestConfiguration {
    @Bean("realQueueAcceptanceFixture")
    fun realQueueAcceptanceFixture() = JdbcQueueAcceptanceFixture()

    @Bean("noHttpWorkerAcceptanceFixture")
    fun noHttpWorkerAcceptanceFixture(
        @Qualifier("realQueueAcceptanceFixture") fixture: JdbcQueueAcceptanceFixture,
    ) = ProcessQueueAcceptanceFixture(fixture)
}

@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = ["com.github.yonaprojects.yona.domain.user", "com.github.yonaprojects.yona.domain.role"])
@Import(Queue::class, TaskRegistry::class, UserServiceImpl::class, MailServiceImpl::class, SchedulerConfig::class, DatabaseInitializer::class)
class QueueAcceptancePersistenceConfiguration {
    @Bean fun dataSource() = SchemaTestDatabase.dataSource()
    @Bean fun entityManagerFactory(dataSource: DataSource) = SchemaTestDatabase.factory(
        dataSource, "com.github.yonaprojects.yona.domain", "com.github.yonaprojects.yona.queue",
    )
    @Bean fun transactionManager(entityManagerFactory: EntityManagerFactory) = JpaTransactionManager(entityManagerFactory)
    @Bean @Primary fun entityManager(entityManagerFactory: EntityManagerFactory): EntityManager =
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)
    @Bean @Primary fun acceptanceQueueClock(dataSource: DataSource) = AcceptanceQueueClock(dataSource)
    @Bean fun mailSender() = JavaMailSenderImpl()
}

/** Store-fixture implementation plus actual two-JVM worker orchestration. */
class JdbcQueueAcceptanceFixture : RealQueueAcceptanceFixture {
    private val context = AnnotationConfigApplicationContext(QueueAcceptancePersistenceConfiguration::class.java)
    override val dataSource: DataSource get() = context.getBean(DataSource::class.java)
    override val transactionManager: PlatformTransactionManager get() = context.getBean(PlatformTransactionManager::class.java)
    override val queue: Queue get() = context.getBean(Queue::class.java)
    internal val registry: TaskRegistry get() = context.getBean(TaskRegistry::class.java)
    internal val jdbc: JdbcTemplate get() = JdbcTemplate(dataSource)
    internal fun contextUserService() = context.getBean(UserService::class.java)
    internal fun contextQueueClock() = context.getBean(QueueClock::class.java)
    internal val dataDirectory: Path = Files.createTempDirectory("yona-queue-worker-data-")
    internal val controlDirectory: Path = Files.createTempDirectory("yona-queue-worker-control-")

    init {
        context.getBean(CommandLineRunner::class.java).run()
        createWorkerObservationTables()
    }

    override fun createAcceptanceMarkerTable() {
        createTableIfMissing("queue_acceptance_marker") {
            "CREATE TABLE queue_acceptance_marker (marker_id VARCHAR(36) PRIMARY KEY)"
        }
    }

    override fun registerStoreAcceptanceTasks() {
        for (type in listOf("queue.acceptance.store.v1", "queue.acceptance.other.v1")) {
            for (version in 1..2) {
                if (registry.find(type, version) == null) registry.register(TaskDefinition(type, version, { node ->
                    require(node.properties().all { it.key in setOf("marker", "left", "right") })
                }))
            }
        }
        if (registry.find("queue.acceptance.resources.v1", 1) == null) {
            registry.register(TaskDefinition(
                "queue.acceptance.resources.v1", 1, { node -> require(node.isEmpty) },
                { listOf("repo:7", "repo:2", "repo:7") },
            ))
        }
    }

    internal fun registerWorkerTasks() {
        QueueAcceptanceWorkerTasks.definitions(dataSource, controlDirectory).forEach { definition ->
            if (registry.find(definition.type, definition.payloadVersion) == null) registry.register(definition)
        }
    }

    override fun jobRow(jobId: Long): QueueJobRow? = jdbc.query(
        "SELECT id,status,payload_version,execution_generation,generation_attempt_no,attempt_count,failure_disposition " +
            "FROM queue_job WHERE id = ?",
        { row, _ -> QueueJobRow(
            row.getLong(1), row.getString(2), row.getInt(3), row.getLong(4), row.getInt(5), row.getLong(6), row.getString(7),
        ) },
        jobId,
    ).singleOrNull()

    override fun attemptRows(jobId: Long): List<QueueAttemptRow> = jdbc.query(
        "SELECT attempt_no,execution_generation,generation_attempt_no,outcome,fence " +
            "FROM queue_attempt WHERE job_id = ? ORDER BY attempt_no",
        { row, _ -> QueueAttemptRow(row.getLong(1), row.getLong(2), row.getInt(3), row.getString(4), row.getLong(5)) },
        jobId,
    )

    override fun idempotencyRowCount(jobId: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM queue_idempotency_key WHERE job_id = ?", Int::class.java, jobId,
    )!!

    override fun seedExistingYonaRowsThroughProductionServices() {
        context.getBean(UserService::class.java).createUser(User(
            loginId = "queue-upgrade-${UUID.randomUUID()}", name = "큐 업그레이드 원본", email = "fixture@example.invalid",
        ))
        for (table in listOf(
            "queue_artifact", "queue_admin_audit", "queue_resource_lock", "queue_job_resource",
            "queue_idempotency_key", "queue_attempt", "queue_job", "queue_meta",
        )) dropTableIfPresent(table)
    }

    override fun snapshotUnrelatedYonaRows(): Map<String, String> = jdbc.query(
        "SELECT id,login_id,name,email FROM n4user ORDER BY id",
        { row, _ -> row.getLong(1).toString() to listOf(row.getString(2), row.getString(3), row.getString(4)).joinToString("\u0000") },
    ).toMap()

    override fun applyQueueOnlyUpgradeToExistingYonaSchema() {
        val migration = SchemaTestDatabase.factory(dataSource, "com.github.yonaprojects.yona.queue")
        migration.afterPropertiesSet()
        migration.destroy()
        queue.afterPropertiesSet()
    }

    override fun reopenOnSamePersistentDatabase(): RealQueueAcceptanceFixture {
        close()
        return JdbcQueueAcceptanceFixture()
    }

    override fun close() {
        context.close()
        deleteTree(dataDirectory)
        deleteTree(controlDirectory)
    }

    internal fun createAcceptanceResource(resourceKey: String) {
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM queue_acceptance_resource_activity WHERE resource_key = ?", Int::class.java, resourceKey,
        )!!
        if (count == 0) jdbc.update(
            "INSERT INTO queue_acceptance_resource_activity(resource_key, active_count, max_active) VALUES (?, 0, 0)",
            resourceKey,
        )
    }

    internal fun createWorkerObservationTables() {
        createTableIfMissing("queue_test_clock") {
            "CREATE TABLE queue_test_clock (singleton_id INTEGER PRIMARY KEY, offset_ms BIGINT NOT NULL)"
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM queue_test_clock WHERE singleton_id = 1", Int::class.java) == 0) {
            jdbc.update("INSERT INTO queue_test_clock(singleton_id, offset_ms) VALUES (1, 0)")
        } else {
            jdbc.update("UPDATE queue_test_clock SET offset_ms = 0 WHERE singleton_id = 1")
        }
        createTableIfMissing("queue_acceptance_worker_start") {
            "CREATE TABLE queue_acceptance_worker_start (" +
                "start_id VARCHAR(36) PRIMARY KEY, job_id BIGINT NOT NULL, attempt_no BIGINT NOT NULL, " +
                "owner_instance VARCHAR(128) NOT NULL)"
        }
        createTableIfMissing("queue_acceptance_resource_activity") {
            "CREATE TABLE queue_acceptance_resource_activity (" +
                "resource_key VARCHAR(300) PRIMARY KEY, active_count INTEGER NOT NULL, max_active INTEGER NOT NULL)"
        }
        createTableIfMissing("queue_acceptance_effect") {
            "CREATE TABLE queue_acceptance_effect (job_id BIGINT PRIMARY KEY)"
        }
        createTableIfMissing("queue_acceptance_fenced_mutation") {
            "CREATE TABLE queue_acceptance_fenced_mutation (" +
                "request_id VARCHAR(36) PRIMARY KEY, job_id BIGINT NOT NULL, fence BIGINT NOT NULL, value_text VARCHAR(2000) NOT NULL)"
        }
        for (table in listOf(
            "queue_acceptance_worker_start", "queue_acceptance_resource_activity",
            "queue_acceptance_effect", "queue_acceptance_fenced_mutation",
        )) jdbc.update("DELETE FROM $table")
    }

    private fun createTableIfMissing(name: String, ddl: () -> String) {
        if (!tableExists(name)) jdbc.execute(ddl())
    }

    private fun dropTableIfPresent(name: String) {
        if (tableExists(name)) jdbc.execute("DROP TABLE $name")
    }

    private fun tableExists(name: String): Boolean = dataSource.connection.use { connection ->
        connection.metaData.getTables(connection.catalog, null, "%", arrayOf("TABLE")).use { rows ->
            var exists = false
            while (rows.next()) if (rows.getString("TABLE_NAME").equals(name, true)) exists = true
            exists
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

class ProcessQueueAcceptanceFixture(
    private val database: JdbcQueueAcceptanceFixture,
) : NoHttpWorkerAcceptanceFixture, RealQueueAcceptanceFixture by database {
    private val processes = ConcurrentHashMap<String, ChildQueueWorkerProcess>()
    private val jdbc get() = database.jdbc
    private val control get() = database.controlDirectory

    override fun registerWorkerAcceptanceTasks() = database.registerWorkerTasks()

    override fun enqueueWorkerScenario(
        scenario: String,
        resourceKey: String,
        payload: ByteArray,
        publicationPath: String?,
        dueAt: Instant?,
    ): Long {
        val type = QueueAcceptanceWorkerTasks.type(scenario)
        val version = if (scenario == "unsupported-payload-version") 2 else 1
        if (version == 1) check(database.registry.find(type, version) != null) { "Register acceptance tasks before enqueue" }
        if (version == 1) database.createAcceptanceResource(resourceKey)
        val bytes = QueueAcceptanceWorkerTasks.payload(resourceKey, publicationPath, payload)
        return TransactionTemplate(transactionManager).execute {
            queue.enqueue(type, version, bytes, dueAt ?: Instant.EPOCH, null, "queue-worker-acceptance").jobId
        }!!
    }

    override fun startRealWorkerProcess(
        instanceId: String, shutdownGraceMillis: Long, heartbeatMillis: Long, workers: Int,
    ): QueueWorkerProcess {
        require(instanceId.isNotBlank() && processes[instanceId] == null)
        val hikari = dataSource as? HikariDataSource ?: error("SchemaTestDatabase must provide HikariDataSource")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("yona.test.runtime-classpath")?.takeIf { it.isNotBlank() }
            ?: throw TestAbortedException("SETUP: Gradle did not expose this test task's runtime classpath")
        val command = mutableListOf(java, "-Dh2.bindAddress=127.0.0.1", "-cp", classpath, QueueWorkerProcessMain::class.java.name)
        val applicationData = database.dataDirectory.resolve("applications").resolve(instanceId).toAbsolutePath()
        val properties = linkedMapOf(
            "spring.profiles.active" to "test",
            "spring.main.web-application-type" to "none",
            "spring.main.banner-mode" to "off",
            "spring.datasource.url" to hikari.jdbcUrl,
            "spring.datasource.username" to hikari.username,
            "spring.datasource.password" to hikari.password,
            "spring.datasource.driver-class-name" to checkNotNull(hikari.driverClassName),
            "spring.datasource.hikari.connection-timeout" to "10000",
            "spring.datasource.hikari.maximum-pool-size" to "4",
            "spring.datasource.hikari.minimum-idle" to "0",
            "spring.jpa.hibernate.ddl-auto" to "none",
            "spring.jpa.properties.hibernate.jdbc.time_zone" to "UTC",
            "spring.ai.mcp.server.enabled" to "false",
            "yona.data" to applicationData.toString(),
            "yona.git.base-dir" to applicationData.resolve("git").toString(),
            "yona.lfs.base-dir" to applicationData.resolve("lfs").toString(),
            "yona.upload.base-dir" to applicationData.resolve("uploads").toString(),
            "yona.oauth2.signing-key-path" to applicationData.resolve("oauth2/signing-key.json").toString(),
            "yona.ssh.relay.enabled" to "false",
            "yona.ssh.mina.enabled" to "false",
            "yona.notification.bymail.enabled" to "false",
            "yona.mailbox.imap.enabled" to "false",
            "yona.ldap.enabled" to "false",
            "yona.queue.instance-id" to instanceId,
            "yona.queue.data-dir" to database.dataDirectory.toAbsolutePath().toString(),
            "yona.queue.acceptance.control-dir" to database.controlDirectory.toAbsolutePath().toString(),
            "yona.queue.workers" to workers.toString(),
            "yona.queue.poll-millis" to "1000",
            "yona.queue.lease-millis" to "60000",
            "yona.queue.heartbeat-millis" to heartbeatMillis.toString(),
            "yona.queue.shutdown-grace-millis" to shutdownGraceMillis.toString(),
            "yona.queue.recovery-poll-millis" to "250",
        )
        if (SchemaTestDatabase.kind == "cubrid") {
            properties["spring.jpa.database-platform"] = "com.github.yonaprojects.yona.config.YonaCubridDialect"
            properties["spring.jpa.hibernate.naming.physical-strategy"] =
                "com.github.yonaprojects.yona.config.YonaCubridNamingStrategy"
            properties["spring.jpa.mapping-resources"] = "META-INF/orm-cubrid.xml"
            properties["spring.jpa.properties.hibernate.jdbc.use_get_generated_keys"] = "false"
            properties["spring.datasource.hikari.connection-test-query"] = "SELECT 1"
        }
        if (SchemaTestDatabase.kind == "mssql") {
            properties["spring.jpa.properties.hibernate.use_nationalized_character_data"] = "true"
        }
        if (SchemaTestDatabase.kind == "h2") {
            properties["spring.jpa.database-platform"] = "org.hibernate.dialect.H2Dialect"
        }
        properties.forEach { (name, value) -> command += "--$name=$value" }
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (failure: Exception) {
            throw TestAbortedException("SETUP: could not launch worker JVM $instanceId", failure)
        }
        val child = ChildQueueWorkerProcess(instanceId, process) { releaseAllGates() }
        check(processes.putIfAbsent(instanceId, child) == null)
        return child
    }
    override fun awaitHandlerGate(jobId: Long, timeoutMillis: Long) {
        waitUntil(timeoutMillis, "handler gate for job $jobId") {
            val directory = control.resolve("gates").resolve(jobId.toString())
            Files.isDirectory(directory) && Files.list(directory).use { paths ->
                paths.anyMatch { it.fileName.toString().endsWith(".entered") }
            }
        }
        check(jobRow(jobId)?.status == "RUNNING")
        check(taskStartCount(jobId) > 0)
    }

    override fun releaseHandlerGate(jobId: Long) {
        val directory = control.resolve("gates").resolve(jobId.toString())
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("release"), "release")
    }

    override fun awaitHandlerReturned(workerInstanceId: String, jobId: Long, timeoutMillis: Long) {
        waitUntil(timeoutMillis, "handler return for $workerInstanceId/$jobId") {
            val directory = control.resolve("returns").resolve(workerInstanceId)
            Files.isDirectory(directory) && Files.list(directory).use { paths ->
                paths.anyMatch { it.fileName.toString().startsWith("$jobId-") && it.fileName.toString().endsWith(".returned") }
            }
        }
    }

    override fun seededSiteAdminActorId(): Long {
        val user = database.contextUserService().createUser(User(
            loginId = "queue-worker-admin-${UUID.randomUUID()}",
            name = "Queue acceptance admin",
            email = "queue-worker-${UUID.randomUUID()}@example.invalid",
            state = UserState.SITE_ADMIN,
        ))
        return checkNotNull(user.id)
    }

    override fun cancelDirectly(jobId: Long, commandId: String, actorId: Long) {
        val gate = control.resolve("gates").resolve(jobId.toString())
        val owner = Files.list(gate).use { files ->
            files.filter { it.fileName.toString().endsWith(".entered") }
                .findFirst().orElseThrow().let { Files.readString(it) }
        }
        val worker = processes[owner] ?: error("Owning worker JVM $owner is not running")
        worker.cancelDirectly(jobId, commandId, actorId)
    }

    override fun awaitJobStatusFromSql(jobId: Long, status: String, timeoutMillis: Long): QueueJobRow {
        waitUntil(timeoutMillis, "job $jobId status $status") { jobRow(jobId)?.status == status }
        return checkNotNull(jobRow(jobId))
    }

    override fun awaitClaimBlockedByResource(jobId: Long, workerCount: Int, timeoutMillis: Long) {
        check(processes.values.count { it.isAlive } >= workerCount) { "Expected $workerCount live worker JVMs" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            check(jobRow(jobId)?.status == "QUEUED") { "Resource-protected job $jobId was claimed" }
            check(attemptRows(jobId).isEmpty()) { "Resource-protected job $jobId has an attempt" }
            check(taskStartCount(jobId) == 0) { "Resource-protected job $jobId entered its handler" }
            Thread.sleep(25)
        }
    }

    override fun maxConcurrentEffects(resourceKey: String): Int = jdbc.queryForObject(
        "SELECT max_active FROM queue_acceptance_resource_activity WHERE resource_key = ?", Int::class.java, resourceKey,
    )!!

    override fun taskStartCount(jobId: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM queue_acceptance_worker_start WHERE job_id = ?", Int::class.java, jobId,
    )!!

    override fun effectCount(runId: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM queue_acceptance_effect WHERE job_id = ?", Int::class.java, runId.toLong(),
    )!!
    override fun nextAttemptAt(jobId: Long): Long? = jdbc.query(
        "SELECT next_attempt_at_epoch_ms FROM queue_job WHERE id = ?",
        { row, _ -> row.getLong(1).let { if (row.wasNull()) null else it } },
        jobId,
    ).singleOrNull()

    override fun retryDelayMillis(jobId: Long): Long = jdbc.queryForObject(
        "SELECT j.next_attempt_at_epoch_ms - a.finished_at_epoch_ms FROM queue_job j " +
            "JOIN queue_attempt a ON a.job_id = j.id AND a.attempt_no = j.attempt_count WHERE j.id = ?",
        Long::class.javaObjectType, jobId,
    )!!

    override fun databaseClaimContenderCount(jobId: Long): Long {
        val directory = control.resolve("claim-barrier").resolve(jobId.toString())
        return if (Files.isDirectory(directory)) Files.list(directory).use { it.count() } else 0
    }


    override fun databaseNow(): Long = contextClock().now()

    override fun advanceTestDatabaseClockBy(millis: Long) {
        require(millis > 0)
        check(jdbc.update("UPDATE queue_test_clock SET offset_ms = offset_ms + ? WHERE singleton_id = 1", millis) == 1)
    }
    override fun retardTestDatabaseClockBy(millis: Long) {
        require(millis > 0)
        check(jdbc.update("UPDATE queue_test_clock SET offset_ms = offset_ms - ? WHERE singleton_id = 1", millis) == 1)
    }


    override fun requestFencedDatabaseMutation(
        workerInstanceId: String,
        jobId: Long,
        requestId: String,
        value: String,
    ): FencedActionObservation = sendProbe(
        workerInstanceId, jobId, requestId, listOf("db", Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))),
    )

    override fun requestFencedFilePublication(
        workerInstanceId: String,
        jobId: Long,
        requestId: String,
        relativePath: String,
        bytes: ByteArray,
    ): FencedActionObservation = sendProbe(workerInstanceId, jobId, requestId, listOf(
        "file", Base64.getEncoder().encodeToString(relativePath.toByteArray(Charsets.UTF_8)), Base64.getEncoder().encodeToString(bytes),
    ))

    override fun committedFenceMutation(requestId: String): PersistedFenceMutation? = jdbc.query(
        "SELECT request_id,job_id,fence,value_text FROM queue_acceptance_fenced_mutation WHERE request_id = ?",
        { row, _ -> PersistedFenceMutation(row.getString(1), row.getLong(2), row.getLong(3), row.getString(4)) },
        requestId,
    ).singleOrNull()

    override fun visiblePublishedArtifact(relativePath: String): PublishedQueueArtifact? =
        PublishedArtifactReader.read(dataSource, database.dataDirectory, relativePath)

    override fun close() {
        releaseAllGates()
        processes.values.toList().forEach { runCatching { it.close() } }
        processes.clear()
    }

    private fun sendProbe(worker: String, jobId: Long, requestId: String, fields: List<String>): FencedActionObservation {
        require(requestId.matches(Regex("[A-Za-z0-9-]{1,36}")))
        val directory = control.resolve("probes").resolve(worker).resolve(jobId.toString())
        Files.createDirectories(directory)
        val request = directory.resolve("$requestId.request")
        writeAtomically(request, (listOf(requestId) + fields).joinToString("\n"))
        val response = directory.resolve("$requestId.response")
        waitUntil(30_000, "probe response $requestId") { Files.exists(response) }
        val result = Files.readString(response).split('\n')
        check(result.size >= 5 && result[0] == requestId) { "Malformed probe response: $result" }
        val outcome = when (result[4]) {
            "ACCEPTED" -> FencedActionOutcome.ACCEPTED
            "REJECTED_STALE_OWNER" -> FencedActionOutcome.REJECTED_STALE_OWNER
            else -> error("Probe failed in original handler thread: ${result[4]}")
        }
        Files.deleteIfExists(response)
        return FencedActionObservation(requestId, result[1], result[2].toLong(), true, result[3].toLong(), outcome)
    }

    private fun releaseAllGates() {
        val gates = control.resolve("gates")
        if (!Files.isDirectory(gates)) return
        Files.list(gates).use { jobs ->
            jobs.filter { Files.isDirectory(it) }.forEach { job ->
                runCatching { Files.writeString(job.resolve("release"), "release") }
            }
        }
    }

    private fun waitUntil(timeoutMillis: Long, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("Timed out waiting for $what")
    }

    private fun contextClock() = database.contextQueueClock()

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

private class ChildQueueWorkerProcess(
    override val instanceId: String,
    private val process: Process,
    private val beforeClose: () -> Unit,
) : QueueWorkerProcess {
    private val lines = java.util.concurrent.ArrayBlockingQueue<String>(32)
    private val observed = java.util.ArrayDeque<String>(200)
    private val writer = process.outputStream.bufferedWriter()

    init {
        Thread({
            process.inputStream.bufferedReader().useLines { output -> output.forEach { line ->
                synchronized(observed) {
                    if (observed.size == 200) observed.removeFirst()
                    observed.addLast(line)
                }
                if (line.startsWith("QUEUE_ACCEPTANCE_")) lines.offer(line)
            } }
        }, "queue-acceptance-output-$instanceId").apply { isDaemon = true; start() }
    }

    val isAlive get() = process.isAlive

    override fun awaitReady(timeoutMillis: Long) {
        try {
            awaitMessage(timeoutMillis, "QUEUE_ACCEPTANCE_READY:$instanceId")
        } catch (failure: IllegalStateException) {
            throw AssertionError("Worker JVM $instanceId did not become ready", failure)
        }

    }
    fun cancelDirectly(jobId: Long, commandId: String, actorId: Long) {
        command("cancel\t$jobId\t$commandId\t$actorId")
        awaitMessage(30_000, "QUEUE_ACCEPTANCE_CONTROL:$commandId:CANCEL_REQUESTED")
    }

    override fun stopClaimingNewWork() {
        command("stop-claiming")
        awaitMessage(30_000, "QUEUE_ACCEPTANCE_STOPPED:$instanceId")
    }

    override fun killForcibly() {
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
    }

    override fun beginGracefulShutdown() {
        command("close")
        awaitMessage(5000, "QUEUE_ACCEPTANCE_SHUTDOWN_STARTED:$instanceId")
    }

    override fun awaitStopped(timeoutMillis: Long) {
        check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "Worker did not finish graceful shutdown" }
        check(process.exitValue() == 0) { "Worker failed during graceful shutdown: ${diagnostics()}" }
    }

    override fun close() {
        if (!process.isAlive) return
        beforeClose()
        command("close")
        if (!process.waitFor(35, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    private fun command(value: String) {
        synchronized(writer) {
            writer.write(value)
            writer.newLine()
            writer.flush()
        }
    }

    private fun awaitMessage(timeoutMillis: Long, expected: String) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val line = lines.poll(remaining.coerceIn(1, 100_000_000), TimeUnit.NANOSECONDS)
            if (line == expected) return
            if (!process.isAlive) break
        }
        error("Worker JVM $instanceId did not emit $expected; output=${diagnostics()}")
    }

    private fun diagnostics(): String = synchronized(observed) { observed.joinToString(" | ") }
}

