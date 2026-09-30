package com.github.yonaprojects.yona.domain.site

import com.github.yonaprojects.yona.queue.CooperativeTaskCancellation
import com.github.yonaprojects.yona.queue.QueueAdmissionException
import com.github.yonaprojects.yona.queue.QueueWorkerRuntime
import com.github.yonaprojects.yona.queue.QueueWorkerStore
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import com.github.yonaprojects.yona.queue.isQueueTable
import org.springframework.aop.framework.ProxyFactory
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionInterceptor
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit

class DataBackupJobsTest {
    @Test
    fun queuedExportPublishesZipAndQueuedImportsRestoreWithoutBlockingOnThemselvesOrEachOther() {
        Harness().use { h ->
            repeat(300) { h.jdbc.update("INSERT INTO archive_job_marker (marker_id, value_text) VALUES (?, ?)", "row-$it", "original") }
            val file = h.app.resolve("payload.bin").apply { parentFile.mkdirs(); writeBytes(ByteArray(2_100_000) { (it % 251).toByte() }) }
            val export = h.jobs.export(null, "admin:test")
            assertEquals("QUEUED", h.fixture.jobRow(export)?.status)
            assertEquals(0, h.jdbc.queryForObject("SELECT COUNT(*) FROM queue_artifact", Int::class.java))
            h.runtime().use { worker ->
                worker.start()
                h.awaitStatus(export, "SUCCEEDED")
            }
            val artifact = h.jdbc.queryForObject("SELECT storage_path FROM queue_artifact WHERE job_id = ?", String::class.java, export)!!
            val zip = Files.readAllBytes(h.fixture.dataDirectory.resolve(artifact))
            val tables = DataBackupArchiveTestSupport.readTables(h.mapper, zip)
            assertEquals(300, tables.getValue("archive_job_marker").size)
            assertFalse(tables.keys.any(::isQueueTable))
            assertTrue(DataBackupArchiveTestSupport.readEntryNames(zip).contains("files/data/payload.bin"))
            val progress = h.mapper.readTree(h.jdbc.queryForObject("SELECT progress_json FROM queue_job WHERE id = ?", String::class.java, export)!!)
            assertTrue(progress["rows"].longValue() >= 300)
            assertTrue(progress["bytes"].longValue() >= file.length())

            val archive = h.archive("restored")
            val first = h.submit(archive)
            val second = h.submit(archive)
            assertEquals("original", h.jdbc.queryForObject("SELECT value_text FROM archive_job_marker WHERE marker_id = 'row-0'", String::class.java))
            assertArrayEquals(archive, Files.readAllBytes(h.input(first)))
            h.runtime().use { worker ->
                worker.start()
                h.awaitStatus(first, "SUCCEEDED")
                h.awaitStatus(second, "SUCCEEDED")
            }
            assertEquals(listOf("restored"), h.jdbc.queryForList("SELECT value_text FROM archive_job_marker", String::class.java))
            assertEquals("SUCCEEDED", h.fixture.jobRow(export)?.status)
            assertTrue(Files.exists(h.fixture.dataDirectory.resolve(artifact)), "Restore must preserve queue result artifacts")
            assertArrayEquals(archive, Files.readAllBytes(h.input(first)), "Input survives until terminal completion has been reconciled")
        }
    }

    @Test
    fun sameSizeTamperingFailsBeforeAnyRestoreAndRetainsTheInput() {
        Harness().use { h ->
            h.jdbc.update("INSERT INTO archive_job_marker VALUES ('before', 'untouched')")
            val job = h.submit(h.archive("changed"))
            val input = h.input(job)
            val bytes = Files.readAllBytes(input)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(input, bytes)
            h.runtime().use { worker ->
                worker.start()
                h.awaitStatus(job, "FAILED")
            }
            assertEquals("BACKUP_INPUT_REJECTED", h.jdbc.queryForObject("SELECT error_code FROM queue_job WHERE id = ?", String::class.java, job))
            assertEquals(listOf("untouched"), h.jdbc.queryForList("SELECT value_text FROM archive_job_marker", String::class.java))
            assertArrayEquals(bytes, Files.readAllBytes(input))
        }
    }

    @Test
    fun cancellationAfterMutationRequiresRecoveryAndNeverDiscardsUploadedBytes() {
        Harness { real, jdbc ->
            object : DataBackupService by real {
                override fun importSite(input: InputStream, execution: DataBackupExecution?) {
                    execution!!.beginMutation()
                    jdbc.update("INSERT INTO archive_job_marker VALUES ('partial', 'must-reconcile')")
                    jdbc.update("UPDATE queue_job SET status = 'CANCEL_REQUESTED' WHERE id = ?", execution.jobId)
                    throw CooperativeTaskCancellation()
                }
            }
        }.use { h ->
            val bytes = h.archive("new")
            val job = h.submit(bytes)
            h.runtime().use { worker ->
                worker.start()
                h.awaitStatus(job, "RECOVERY_REQUIRED")
            }
            assertEquals("BACKUP_RESTORE_UNCERTAIN", h.jdbc.queryForObject("SELECT error_code FROM queue_job WHERE id = ?", String::class.java, job))
            assertEquals(listOf("must-reconcile"), h.jdbc.queryForList("SELECT value_text FROM archive_job_marker", String::class.java))
            assertArrayEquals(bytes, Files.readAllBytes(h.input(job)))
            assertEquals(1, h.fixture.attemptRows(job).size)
        }
    }

    @Test
    fun rejectedSubmissionDeletesOnlyItsUnadmittedInputAndUnsafeReferencesAreRejected() {
        Harness().use { h ->
            val committed = h.submit(h.archive("retained"))
            assertThrows(QueueAdmissionException::class.java) {
                h.jobs.import(h.archive("rollback").inputStream(), "x".repeat(201))
            }
            Files.list(h.fixture.dataDirectory.resolve("import-inputs")).use { files ->
                assertEquals(listOf(h.input(committed)), files.toList())
            }
            val failure = assertThrows(QueueAdmissionException::class.java) {
                h.fixture.queue.enqueue(DataBackupJobTasks.IMPORT, 1, h.mapper.writeValueAsBytes(mapOf(
                    "input" to "../outside.zip", "size" to 1, "sha256" to "0".repeat(64),
                )), Instant.EPOCH, null, "admin:test")
            }
            assertEquals("INVALID_PAYLOAD", failure.code)
        }
    }

    private class Harness(
        wrap: (DataBackupService, org.springframework.jdbc.core.JdbcTemplate) -> DataBackupService = { service, _ -> service },
    ) : AutoCloseable {
        val fixture = JdbcQueueAcceptanceFixture()
        val jdbc = fixture.jdbc
        val mapper = JsonMapper.builder().build()
        val app = fixture.dataDirectory.resolve("application").toFile()
        private val service = DataBackupServiceImpl(fixture.dataSource, mapper, app, fixture.dataDirectory.toFile(),
            fixture.dataDirectory.resolve("git").toFile(), fixture.dataDirectory.resolve("lfs").toFile(),
            fixture.dataDirectory.resolve("uploads").toFile())
        private val transactional = ProxyFactory(object : DataBackupService by service {
            @Transactional
            override fun importSite(input: InputStream, execution: DataBackupExecution?) {
                service.importSite(input, execution)
                fixture.dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT progress_stage FROM queue_job WHERE id = ?").use { statement ->
                        statement.setLong(1, execution!!.jobId)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals("import.files", rows.getString(1), "Progress must commit before the restore transaction")
                        }
                    }
                }
            }
        }).apply {
            addAdvice(TransactionInterceptor(fixture.transactionManager, AnnotationTransactionAttributeSource()))
        }.proxy as DataBackupService
        private val tasks = DataBackupJobTasks(wrap(transactional, jdbc), mapper, fixture.dataDirectory.toFile(), fixture.transactionManager)
        val jobs = DataBackupJobs(fixture.queue, tasks, mapper, fixture.transactionManager)

        init {
            jdbc.execute("CREATE TABLE IF NOT EXISTS archive_job_marker (marker_id VARCHAR(100) PRIMARY KEY, value_text VARCHAR(100))")
            jdbc.update("DELETE FROM archive_job_marker")
            fixture.registry.register(tasks.dataBackupExportTask())
            fixture.registry.register(tasks.dataBackupImportTask())
        }

        fun archive(value: String) = DataBackupArchiveTestSupport.buildSiteArchive(mapper, mapOf(
            "archive_job_marker" to listOf(mapOf("marker_id" to "restored", "value_text" to value)),
        ))

        fun submit(bytes: ByteArray): Long = jobs.import(object : FilterInputStream(bytes.inputStream()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Upload staging must not hold a database transaction")
                return super.read(buffer, offset, length)
            }
        }, "admin:test")

        fun input(job: Long) = tasks.inputPath(mapper.readTree(jdbc.queryForObject(
            "SELECT payload FROM queue_job WHERE id = ?", ByteArray::class.java, job,
        )!!)["input"].textValue())

        fun runtime(): QueueWorkerRuntime {
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val meters = SimpleMeterRegistry()
            val clock = fixture.contextQueueClock()
            val store = QueueWorkerStore(manager, fixture.transactionManager, clock, fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
            return QueueWorkerRuntime(store, fixture.registry, clock, meters, workers = 2, pollMillis = 20,
                shutdownGraceMillis = 1000, dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4)
        }

        fun awaitStatus(job: Long, status: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (fixture.jobRow(job)?.status != status && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(status, fixture.jobRow(job)?.status)
        }

        override fun close() {
            val jobs = "SELECT id FROM queue_job WHERE task_type IN ('site.backup-export', 'site.backup-import')"
            val failures = jdbc.queryForObject("SELECT COUNT(*) FROM queue_job WHERE id IN ($jobs) AND status = 'FAILED'", Long::class.java)!!
            jdbc.update("UPDATE queue_resource_lock SET current_job_id = NULL, current_attempt_no = NULL, current_fence = NULL, lease_expires_at_epoch_ms = NULL WHERE current_job_id IN ($jobs)")
            for (table in listOf("queue_artifact", "queue_admin_audit", "queue_idempotency_key", "queue_job_resource", "queue_attempt")) {
                jdbc.update("DELETE FROM $table WHERE job_id IN ($jobs)")
            }
            jdbc.update("DELETE FROM queue_job WHERE id IN ($jobs)")
            jdbc.update("UPDATE queue_meta SET counter_value = counter_value - ? WHERE counter_name = 'failed-jobs'", failures)
            jdbc.execute("DROP TABLE archive_job_marker")
            fixture.close()
        }
    }
}
