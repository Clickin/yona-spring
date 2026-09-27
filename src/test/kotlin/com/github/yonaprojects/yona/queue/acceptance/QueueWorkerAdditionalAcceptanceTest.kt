package com.github.yonaprojects.yona.queue.acceptance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.UUID

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [QueueAcceptanceFixtureTestConfiguration::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class QueueWorkerAdditionalAcceptanceTest @Autowired constructor(
    @Qualifier("noHttpWorkerAcceptanceFixture") private val fixture: NoHttpWorkerAcceptanceFixture,
) {
    @Test
    fun twoProcessesContendAtTheDatabaseWithoutAResourceLock() {
        fixture.registerWorkerAcceptanceTasks()
        val first = fixture.startRealWorkerProcess("unguarded-a-${UUID.randomUUID()}")
        val second = fixture.startRealWorkerProcess("unguarded-b-${UUID.randomUUID()}")
        try {
            first.awaitReady(30_000)
            second.awaitReady(30_000)
            val job = fixture.enqueueWorkerScenario("unguarded-claim", resourceKey())
            fixture.awaitHandlerGate(job, 30_000)
            assertEquals(2L, fixture.databaseClaimContenderCount(job))
            assertEquals(1, fixture.attemptRows(job).size)
            assertEquals(1, fixture.taskStartCount(job))
            fixture.releaseHandlerGate(job)
            fixture.awaitJobStatusFromSql(job, "SUCCEEDED", 30_000)
            assertEquals(1, fixture.attemptRows(job).size)
            assertEquals(1, fixture.taskStartCount(job))
        } finally {
            second.close()
            first.close()
        }
    }

    @Test
    fun gracefulShutdownExpiryKeepsTheGuardUntilTheWorkerProcessExits() {
        fixture.registerWorkerAcceptanceTasks()
        val old = fixture.startRealWorkerProcess(
            "shutdown-old-${UUID.randomUUID()}", shutdownGraceMillis = 10_000, heartbeatMillis = 250, workers = 2,
        )
        try {
            old.awaitReady(30_000)
            val resource = resourceKey()
            val running = fixture.enqueueWorkerScenario("unsafe-gated-cancel", resource)
            fixture.awaitHandlerGate(running, 30_000)
            fixture.startRealWorkerProcess("shutdown-next-${UUID.randomUUID()}").use { successor ->
                successor.awaitReady(30_000)
                val jdbc = JdbcTemplate(fixture.dataSource)
                fun heartbeat(): Long = jdbc.queryForObject(
                    "SELECT heartbeat_at_epoch_ms FROM queue_attempt WHERE job_id = ?",
                    Long::class.javaObjectType, running,
                )!!
                val beforeShutdown = heartbeat()
                old.beginGracefulShutdown()
                val heartbeatDeadline = System.nanoTime() + 2_000_000_000L
                while (heartbeat() <= beforeShutdown && System.nanoTime() < heartbeatDeadline) Thread.sleep(20)
                assertTrue(heartbeat() > beforeShutdown, "Heartbeat stopped before the shutdown grace expired")
                val unrelated = fixture.enqueueWorkerScenario("success", resourceKey())
                fixture.awaitJobStatusFromSql(unrelated, "SUCCEEDED", 3000)
                assertEquals(successor.instanceId, jdbc.queryForObject(
                    "SELECT owner_instance FROM queue_attempt WHERE job_id = ?", String::class.java, unrelated,
                ))
                val next = fixture.enqueueWorkerScenario("success", resource)
                fixture.advanceTestDatabaseClockBy(120_000)
                fixture.awaitJobStatusFromSql(running, "RECOVERY_REQUIRED", 3000)
                fixture.awaitClaimBlockedByResource(next, workerCount = 2, timeoutMillis = 1500)
                old.awaitStopped(15_000)
                fixture.awaitJobStatusFromSql(next, "SUCCEEDED", 30_000)
                assertEquals("RECOVERY_REQUIRED", fixture.jobRow(running)?.status)
                assertEquals(listOf("LEASE_LOST"), fixture.attemptRows(running).map { it.status })
                assertEquals(1, fixture.taskStartCount(running))
                assertEquals(1, fixture.taskStartCount(next))
            }
        } finally {
            old.close()
        }
    }

    @Test
    fun gracefulShutdownCheckpointReturnsWithoutInventingATerminalOutcome() {
        fixture.registerWorkerAcceptanceTasks()
        val old = fixture.startRealWorkerProcess(
            "checkpoint-old-${UUID.randomUUID()}", shutdownGraceMillis = 10_000, heartbeatMillis = 250,
        )
        val job: Long
        try {
            old.awaitReady(30_000)
            job = fixture.enqueueWorkerScenario("process-crash-replay", resourceKey())
            fixture.awaitHandlerGate(job, 30_000)
            old.beginGracefulShutdown()
            fixture.releaseHandlerGate(job)
            fixture.awaitHandlerReturned(old.instanceId, job, 5000)
            old.awaitStopped(10_000)
            assertEquals("RUNNING", fixture.jobRow(job)?.status)
            assertEquals(listOf("RUNNING"), fixture.attemptRows(job).map { it.status })
            assertEquals(1, fixture.effectCount(job.toString()))
        } finally {
            old.close()
        }
        fixture.advanceTestDatabaseClockBy(120_000)
        fixture.startRealWorkerProcess("checkpoint-replacement-${UUID.randomUUID()}").use { replacement ->
            replacement.awaitReady(30_000)
            fixture.awaitJobStatusFromSql(job, "SUCCEEDED", 30_000)
            assertEquals(listOf("LEASE_LOST", "SUCCEEDED"), fixture.attemptRows(job).map { it.status })
            assertEquals(1, fixture.effectCount(job.toString()))
        }
    }

    private fun resourceKey() = "extra:${(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)}"
}
